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
import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/** Execution-time connection preparation and health checks; has no Docker or lifecycle operations. */
public final class ExternalConnection {
    private final EmulatorConnection connection;
    private final int timeout;
    private final HttpsHealthProbe health = new HttpsHealthProbe();
    private boolean prepared;

    public ExternalConnection(String endpoint, String region, Path workingDirectory, Path credentials,
            Path certificate, int timeout) {
        String address = validateEndpoint(endpoint);
        if (!region.matches("[a-z0-9-]+")) throw new GradleException("AWS Emulator region must contain lowercase letters, numbers, or hyphens.");
        if (timeout <= 0) throw new GradleException("AWS Emulator startupTimeoutSeconds must be positive.");
        Path directory = workingDirectory.toAbsolutePath().resolve("external").resolve(
            EmulatorSettings.hash(address + "\n" + region + "\n" + credentials.toAbsolutePath() + "\n" + certificate.toAbsolutePath()));
        this.connection = new EmulatorConnection(address, region, directory,
            credentials.toAbsolutePath(), certificate.toAbsolutePath());
        this.timeout = timeout;
    }

    static String validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        } catch (IllegalArgumentException e) {
            throw new GradleException("awsEmulator.external.endpoint must be an absolute HTTPS URL with a hostname "
                + "and optional port, without a path, query, fragment, or embedded credentials.");
        }
    }

    private synchronized void prepare() {
        if (prepared) return;
        EmulatorCredentials.read(connection.credentials());
        if (!Files.isRegularFile(connection.certificate())) {
            throw new GradleException("awsEmulator.external.certificateFile must point to a readable PEM CA certificate bundle.");
        }
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, new char[0]);
            try (var input = Files.newInputStream(connection.certificate())) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw new GradleException("AWS Emulator CA file contains no certificates.");
                int index = 0;
                for (var certificate : certificates) trust.setCertificateEntry("ca-" + index++, certificate);
            }
            Files.createDirectories(connection.directory());
            Path temporary = Files.createTempFile(connection.directory(), "trust-", ".p12");
            try {
                try (var output = Files.newOutputStream(temporary)) { trust.store(output, new char[0]); }
                Files.move(temporary, connection.trustStore(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            connection.writeHostConfig();
            prepared = true;
        } catch (Exception e) {
            throw new GradleException("Cannot prepare AWS Emulator external connection. Check certificateFile "
                + "(PEM format) and workingDirectory permissions.", e);
        }
    }

    public EmulatorConnection connect() {
        prepare();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        Exception lastFailure = null;
        do {
            try {
                if (health.ready(connection.endpoint(), connection.trustStore())) return connection;
            } catch (SSLHandshakeException e) {
                throw new GradleException("Cannot verify AWS Emulator external TLS. Check certificateFile and the endpoint hostname.", e);
            } catch (Exception e) {
                lastFailure = e;
            }
            try { Thread.sleep(200); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted while waiting for the external AWS Emulator.", e);
            }
        } while (System.nanoTime() < deadline);
        throw new GradleException("External AWS Emulator did not become ready at " + connection.endpoint()
            + " within " + timeout + " seconds. Start it separately and check its HTTPS /__admin/health endpoint.", lastFailure);
    }

    public String status() {
        prepare();
        try {
            return "AWS Emulator external instance: " + connection.endpoint() + ", "
                + (health.ready(connection.endpoint(), connection.trustStore()) ? "healthy" : "not ready")
                + "\nLifecycle is managed externally.";
        } catch (Exception e) {
            return "AWS Emulator external instance: " + connection.endpoint()
                + ", not ready (connection or TLS verification failed).\nLifecycle is managed externally.";
        }
    }

    public String exportConnection() {
        EmulatorConnection ready = connect();
        try { ConnectionExports.write(ready, ready.directory()); }
        catch (Exception e) { throw new GradleException("Cannot export AWS Emulator external connection.", e); }
        return "AWS Emulator external connection: " + ready.endpoint()
            + "\nPOSIX shell: " + ready.directory().resolve("connection.sh")
            + "\nPowerShell: " + ready.directory().resolve("connection.ps1");
    }
}
