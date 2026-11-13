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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import static org.junit.jupiter.api.Assertions.*;

class ExternalConnectionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9297", "https://localhost/path", "https://localhost?x=1",
        "https://localhost#fragment", "https://user:private@localhost", "localhost:9297",
        "https://localhost:0", "https://localhost:65536", ""})
    void rejectsInvalidEndpointsWithoutEchoingTheirContents(String endpoint) {
        var failure = assertThrows(GradleException.class, () -> ExternalConnection.validateEndpoint(endpoint));
        assertTrue(failure.getMessage().contains("absolute HTTPS URL"));
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test
    void usesExplicitHostnameFilesAndSessionCredentialsWithoutChangingSources() throws Exception {
        var identity = TlsFixtures.identity(directory, "external");
        TlsFixtures.pem(directory.resolve("source ca.pem"), identity);
        credentials("[default]\naws_access_key_id = external-key\naws_secret_access_key = external-secret\naws_session_token = external-token\n");
        byte[] originalCertificate = Files.readAllBytes(directory.resolve("source ca.pem"));
        byte[] originalCredentials = Files.readAllBytes(directory.resolve("source credentials"));
        try (var server = TlsFixtures.server(identity)) {
            health(server);
            String endpoint = server.url().replace("127.0.0.1", "localhost");
            var runtime = runtime(endpoint);
            var connection = runtime.connect();
            assertEquals(endpoint.substring(0, endpoint.length() - 1), connection.endpoint());
            assertEquals(directory.resolve("source credentials"), connection.credentials());
            assertEquals(directory.resolve("source ca.pem"), connection.certificate());
            assertEquals(connection.certificate().toString(), connection.connectionEnvironment().get("AWS_CA_BUNDLE"));
            assertTrue(Files.readString(connection.directory().resolve("host-config")).contains("ca_bundle = " + connection.certificate()));
            var read = assertInstanceOf(AwsSessionCredentials.class, EmulatorCredentials.read(connection.credentials()));
            assertEquals("external-token", read.sessionToken());
            assertTrue(runtime.status().contains("healthy"));
            runtime.exportConnection();
            assertFalse(Files.readString(connection.directory().resolve("connection.sh")).contains("external-secret"));
            assertArrayEquals(originalCertificate, Files.readAllBytes(connection.certificate()));
            assertArrayEquals(originalCredentials, Files.readAllBytes(connection.credentials()));
        }
    }

    @Test
    void rejectsWrongCertificateAndWrongHostname() throws Exception {
        var trusted = TlsFixtures.identity(directory, "trusted");
        var unrelated = TlsFixtures.identity(directory, "unrelated");
        credentials("[default]\naws_access_key_id = test\naws_secret_access_key = test\n");
        TlsFixtures.pem(directory.resolve("source ca.pem"), unrelated);
        try (var server = TlsFixtures.server(trusted)) {
            health(server);
            assertTrue(assertThrows(GradleException.class, () -> runtime(server.url()).connect())
                .getMessage().contains("Cannot verify AWS Emulator external TLS"));
        }
        var wrongHostname = TlsFixtures.identity(directory, "wrong-host", "dns:another-host.invalid");
        TlsFixtures.pem(directory.resolve("source ca.pem"), wrongHostname);
        try (var server = TlsFixtures.server(wrongHostname)) {
            health(server);
            assertTrue(assertThrows(GradleException.class, () -> runtime(server.url()).connect())
                .getMessage().contains("Cannot verify AWS Emulator external TLS"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[production]\naws_access_key_id = private-key\naws_secret_access_key = private-secret",
        "[default]\naws_access_key_id = private-key", "[default]\nprivate-secret malformed"})
    void rejectsInvalidCredentialsWithoutDisclosingValues(String contents) throws Exception {
        credentials(contents);
        var failure = assertThrows(GradleException.class, () -> EmulatorCredentials.read(directory.resolve("source credentials")));
        assertTrue(failure.getMessage().contains("[default]"));
        assertFalse(failure.toString().contains("private-"));
        assertNull(failure.getCause());
    }

    @Test
    void failsForMissingFilesAndUnreadyEndpoints() throws Exception {
        assertThrows(GradleException.class, () -> runtime("https://localhost:9297").connect());
        credentials("[default]\naws_access_key_id = test\naws_secret_access_key = test\n");
        assertTrue(assertThrows(GradleException.class, () -> runtime("https://localhost:9297").connect())
            .getMessage().contains("certificateFile"));
        var identity = TlsFixtures.identity(directory, "unready");
        TlsFixtures.pem(directory.resolve("source ca.pem"), identity);
        try (var server = TlsFixtures.server(identity)) {
            var runtime = runtime(server.url());
            assertTrue(runtime.status().contains("not ready"));
            assertTrue(assertThrows(GradleException.class, runtime::connect).getMessage().contains("did not become ready"));
        }
    }

    private ExternalConnection runtime(String endpoint) {
        return new ExternalConnection(endpoint, "us-east-1", directory.resolve("working"),
            directory.resolve("source credentials"), directory.resolve("source ca.pem"), 1);
    }

    private void credentials(String contents) throws Exception { Files.writeString(directory.resolve("source credentials"), contents); }

    private static void health(TlsFixtures.Endpoint endpoint) {
        endpoint.server().createContext("/__admin/health", exchange -> {
            byte[] response = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
    }
}
