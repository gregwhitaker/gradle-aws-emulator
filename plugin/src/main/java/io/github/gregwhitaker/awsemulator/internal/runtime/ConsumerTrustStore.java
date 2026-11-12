/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.gradle.api.GradleException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Builds certificate-only trust stores for consumers without modifying source stores or daemon TLS. */
public final class ConsumerTrustStore {
    // Contains public certificates only. This password enables PKCS12 integrity checks, not secret protection.
    public static final String PASSWORD = "changeit";
    private ConsumerTrustStore() {}

    public static Path create(EmulatorConnection connection, Path javaHome, Path workingDirectory,
            Map<String, String> properties) {
        try {
            Map<String, Certificate> certificates = new TreeMap<>();
            Path defaults = javaHome.resolve("lib/security/jssecacerts");
            if (!Files.exists(defaults)) defaults = javaHome.resolve("lib/security/cacerts");
            add(certificates, load(defaults, null, "", null));

            String custom = properties.get("javax.net.ssl.trustStore");
            if (custom != null && !custom.isEmpty()) {
                String password = properties.get("javax.net.ssl.trustStorePassword");
                char[] secret = password == null ? null : password.toCharArray();
                try {
                    Path source = custom.equals("NONE") ? null : workingDirectory.resolve(custom).normalize();
                    add(certificates, load(source, properties.get("javax.net.ssl.trustStoreType"),
                        properties.getOrDefault("javax.net.ssl.trustStoreProvider", ""), secret));
                } finally {
                    if (secret != null) Arrays.fill(secret, '\0');
                }
            }
            try (var input = Files.newInputStream(connection.directory().resolve("ca.pem"))) {
                var emulatorCertificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (emulatorCertificates.isEmpty()) throw new GradleException("AWS Emulator CA file contains no certificates.");
                for (Certificate certificate : emulatorCertificates) add(certificates, certificate);
            }

            KeyStore merged = KeyStore.getInstance("PKCS12");
            merged.load(null, PASSWORD.toCharArray());
            for (var entry : certificates.entrySet()) merged.setCertificateEntry(entry.getKey(), entry.getValue());

            // A content-specific path prevents consumers with different trust settings from overwriting each other.
            String identity = EmulatorSettings.hash(String.join("\n", certificates.keySet()));
            Path directory = connection.directory().resolve("consumer-truststores");
            Files.createDirectories(directory);
            Path target = directory.resolve(identity + ".p12");
            Path temporary = Files.createTempFile(directory, "trust-", ".p12");
            try {
                try (var output = Files.newOutputStream(temporary)) { merged.store(output, PASSWORD.toCharArray()); }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return target.toAbsolutePath();
        } catch (Exception e) {
            throw new GradleException("Cannot prepare AWS Emulator consumer TLS trust. Check the selected Java installation "
                + "and javax.net.ssl.trustStore, trustStoreType, trustStoreProvider, and trustStorePassword settings.", e);
        }
    }

    private static KeyStore load(Path path, String type, String provider, char[] password) throws Exception {
        KeyStore store;
        if (path != null && (type == null || type.isEmpty()) && provider.isEmpty()) {
            store = KeyStore.getInstance(path.toFile(), password);
        } else {
            String selectedType = type == null || type.isEmpty() ? KeyStore.getDefaultType() : type;
            store = provider.isEmpty() ? KeyStore.getInstance(selectedType) : KeyStore.getInstance(selectedType, provider);
            try (InputStream input = path == null ? null : Files.newInputStream(path)) { store.load(input, password); }
        }
        // PKCS12 may silently skip encrypted certificates when given null. An empty password either loads
        // an unprotected store or reports a missing password instead of silently losing the custom roots.
        if (path != null && password == null && store.size() == 0 && store.getType().equalsIgnoreCase("PKCS12")) {
            try (var input = Files.newInputStream(path)) { store.load(input, new char[0]); }
        }
        return store;
    }

    private static void add(Map<String, Certificate> certificates, KeyStore source) throws Exception {
        var aliases = source.aliases();
        while (aliases.hasMoreElements()) {
            // JSSE also trusts the certificate of a private-key entry. Never copy the key or promote its entire chain.
            Certificate certificate = source.getCertificate(aliases.nextElement());
            if (certificate != null) add(certificates, certificate);
        }
    }

    private static void add(Map<String, Certificate> certificates, Certificate certificate) throws Exception {
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        certificates.put(fingerprint, certificate);
    }
}
