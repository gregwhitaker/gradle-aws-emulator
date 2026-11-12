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

import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerTrustStoreTest {
    @TempDir static Path identities;
    @TempDir Path directory;
    static KeyStore defaults, custom, emulator, unrelated;

    @BeforeAll
    static void identities() throws Exception {
        defaults = TlsFixtures.identity(identities, "default");
        custom = TlsFixtures.identity(identities, "custom");
        emulator = TlsFixtures.identity(identities, "emulator");
        unrelated = TlsFixtures.identity(identities, "unrelated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"JKS", "PKCS12"})
    void trustsDefaultCustomAndEmulatorServersButRejectsUnrelatedCertificates(String type) throws Exception {
        EmulatorConnection connection = fixture();
        Path source = directory.resolve("custom trust store");
        TlsFixtures.trustStore(source, type, TlsFixtures.PASSWORD, custom);
        byte[] original = Files.readAllBytes(source);
        String parentTrustStore = System.getProperty("javax.net.ssl.trustStore");
        Path merged = ConsumerTrustStore.create(connection, javaHome(), directory, Map.of(
            "javax.net.ssl.trustStore", source.getFileName().toString(),
            "javax.net.ssl.trustStoreType", type, "javax.net.ssl.trustStorePassword", TlsFixtures.PASSWORD,
            "javax.net.ssl.trustStoreProvider", "SUN"));
        SSLContext context = SSLContext.getInstance("TLS");
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(merged.toFile(), ConsumerTrustStore.PASSWORD.toCharArray()));
        context.init(null, trust.getTrustManagers(), null);
        HttpClient client = HttpClient.newBuilder().sslContext(context).build();
        for (KeyStore identity : new KeyStore[] {defaults, custom, emulator}) {
            try (var server = TlsFixtures.server(identity)) {
                assertEquals("trusted", client.send(HttpRequest.newBuilder(URI.create(server.url())).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            }
        }
        try (var server = TlsFixtures.server(unrelated)) {
            assertThrows(SSLHandshakeException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create(server.url())).build(), HttpResponse.BodyHandlers.ofString()));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(parentTrustStore, System.getProperty("javax.net.ssl.trustStore"));
        assertEquals(1, KeyStore.getInstance(connection.trustStore().toFile(), new char[0]).size());
        assertEquals(3, KeyStore.getInstance(merged.toFile(), ConsumerTrustStore.PASSWORD.toCharArray()).size());
    }

    @Test
    void usesSelectedJdkDefaultsAndPrefersJssecacerts() throws Exception {
        EmulatorConnection connection = fixture();
        Path first = ConsumerTrustStore.create(connection, javaHome(), directory, Map.of());
        assertTrusted(first, defaults, true);
        assertTrusted(first, emulator, true);
        TlsFixtures.trustStore(javaHome().resolve("lib/security/jssecacerts"), "JKS", "changeit", custom);
        Path second = ConsumerTrustStore.create(connection, javaHome(), directory, Map.of());
        assertTrusted(second, custom, true);
        assertTrusted(second, defaults, false);
        assertNotEquals(first, second);
    }

    @Test
    void keepsDifferentConsumersIsolatedAndReloadsChangedCertificates() throws Exception {
        EmulatorConnection connection = fixture();
        Path source = directory.resolve("custom.p12");
        TlsFixtures.trustStore(source, "PKCS12", TlsFixtures.PASSWORD, custom);
        Map<String, String> options = Map.of("javax.net.ssl.trustStore", source.toString(),
            "javax.net.ssl.trustStorePassword", TlsFixtures.PASSWORD);
        Path first = ConsumerTrustStore.create(connection, javaHome(), directory, options);
        Path plain = ConsumerTrustStore.create(connection, javaHome(), directory, Map.of());
        assertTrusted(plain, custom, false);
        TlsFixtures.trustStore(source, "PKCS12", TlsFixtures.PASSWORD, unrelated);
        Path changed = ConsumerTrustStore.create(connection, javaHome(), directory, options);
        assertNotEquals(first, changed);
        assertTrusted(first, custom, true);
        assertTrusted(changed, custom, false);
        assertTrusted(changed, unrelated, true);
    }

    @Test
    void copiesCertificatesFromKeyEntriesWithoutExportingPrivateKeys() throws Exception {
        EmulatorConnection connection = fixture();
        Path merged = ConsumerTrustStore.create(connection, javaHome(), directory, Map.of(
            "javax.net.ssl.trustStore", identities.resolve("custom.p12").toString(),
            "javax.net.ssl.trustStorePassword", TlsFixtures.PASSWORD));
        KeyStore store = KeyStore.getInstance(merged.toFile(), ConsumerTrustStore.PASSWORD.toCharArray());
        assertTrusted(merged, custom, true);
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) assertTrue(store.isCertificateEntry(aliases.nextElement()));
    }

    @Test
    void failsForMissingCorruptOrIncorrectlyProtectedSources() throws Exception {
        EmulatorConnection connection = fixture();
        Path source = directory.resolve("custom.jks");
        assertThrows(GradleException.class, () -> ConsumerTrustStore.create(connection, javaHome(), directory,
            Map.of("javax.net.ssl.trustStore", source.toString())));
        TlsFixtures.trustStore(source, "JKS", TlsFixtures.PASSWORD, custom);
        GradleException failure = assertThrows(GradleException.class, () -> ConsumerTrustStore.create(connection, javaHome(), directory,
            Map.of("javax.net.ssl.trustStore", source.toString(), "javax.net.ssl.trustStorePassword", "incorrect")));
        assertTrue(failure.getMessage().contains("consumer TLS trust"));
        assertFalse(failure.getMessage().contains("incorrect"));
        TlsFixtures.trustStore(source, "PKCS12", TlsFixtures.PASSWORD, custom);
        assertThrows(GradleException.class, () -> ConsumerTrustStore.create(connection, javaHome(), directory,
            Map.of("javax.net.ssl.trustStore", source.toString())));
        Files.writeString(javaHome().resolve("lib/security/cacerts"), "corrupt");
        assertThrows(GradleException.class, () -> ConsumerTrustStore.create(connection, javaHome(), directory, Map.of()));
    }

    @Test
    void retainsHostnameVerificationForTrustedCertificates() throws Exception {
        EmulatorConnection connection = fixture();
        KeyStore wrongHost = TlsFixtures.identity(directory, "wrong-host", "dns:wrong.example");
        Path source = directory.resolve("custom.jks");
        TlsFixtures.trustStore(source, "JKS", TlsFixtures.PASSWORD, wrongHost);
        Path merged = ConsumerTrustStore.create(connection, javaHome(), directory,
            Map.of("javax.net.ssl.trustStore", source.toString(), "javax.net.ssl.trustStorePassword", TlsFixtures.PASSWORD));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(merged.toFile(), ConsumerTrustStore.PASSWORD.toCharArray()));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        try (var server = TlsFixtures.server(wrongHost)) {
            HttpClient client = HttpClient.newBuilder().sslContext(context).build();
            assertThrows(SSLHandshakeException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create(server.url())).build(), HttpResponse.BodyHandlers.ofString()));
        }
    }

    private Path javaHome() { return directory.resolve("selected-jdk"); }

    private EmulatorConnection fixture() throws Exception {
        TlsFixtures.trustStore(javaHome().resolve("lib/security/cacerts"), "JKS", "changeit", defaults);
        EmulatorConnection connection = new EmulatorConnection(9297, "us-east-1", directory.resolve("emulator"));
        TlsFixtures.trustStore(connection.trustStore(), "PKCS12", "", emulator);
        TlsFixtures.pem(connection.directory().resolve("ca.pem"), emulator);
        return connection;
    }

    private void assertTrusted(Path path, KeyStore identity, boolean expected) throws Exception {
        KeyStore store = KeyStore.getInstance(path.toFile(), ConsumerTrustStore.PASSWORD.toCharArray());
        assertEquals(expected, store.getCertificateAlias(TlsFixtures.certificate(identity)) != null);
    }
}
