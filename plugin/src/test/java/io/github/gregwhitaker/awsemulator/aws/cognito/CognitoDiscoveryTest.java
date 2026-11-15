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

package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CognitoDiscoveryTest {
    private static final String POOL = "us-east-1_audited";
    @TempDir Path directory;
    private TlsFixtures.Endpoint server;
    private EmulatorConnection runtime;
    private String body;
    private int status = 200;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach void startDiscoveryServer() throws Exception {
        var identity = TlsFixtures.identity(directory, "discovery");
        TlsFixtures.trustStore(directory.resolve("truststore.p12"), "PKCS12", "", identity);
        server = TlsFixtures.server(identity);
        String endpoint = server.url().substring(0, server.url().length() - 1);
        runtime = new EmulatorConnection(endpoint, "us-east-1", directory, directory.resolve("unused"), directory.resolve("unused.pem"));
        server.server().removeContext("/");
        server.server().createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (status == 302) exchange.getResponseHeaders().add("Location", server.url() + "redirected");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    @AfterEach void stopDiscoveryServer() { if (server != null) server.close(); }

    @Test void discoversOriginalUpdatedAndLegacyIssuersOnlyOverTheLocalConnection() {
        for (String endpoint : List.of("https://cognito-idp.us-east-1.amazonaws.com",
                "https://issuer-cognito-idp.us-east-1.amazonaws.com", runtime.endpoint())) {
            String issuer = endpoint + "/" + POOL;
            body = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"https://unreachable.invalid/keys\"}";
            assertEquals(issuer, CognitoDiscovery.issuer(runtime, POOL, 5));
        }
        assertEquals(List.of("/" + POOL + "/.well-known/openid-configuration",
            "/" + POOL + "/.well-known/openid-configuration", "/" + POOL + "/.well-known/openid-configuration"), requests);
    }

    @Test void rejectsMissingMalformedOrForeignPoolIssuers() {
        for (String document : List.of("{}", "{\"issuer\":null}", "{\"issuer\":42}", "broken-json",
                "{\"issuer\":\"http://example.test/" + POOL + "\"}",
                "{\"issuer\":\"https://example.test/another-pool\"}",
                "{\"issuer\":\"https://user@example.test/" + POOL + "\"}",
                "{\"issuer\":\"https://example.test/" + POOL + "?query=value\"}",
                "{\"issuer\":\"https://example.test/" + POOL + "#fragment\"}")) {
            body = document;
            assertThrows(GradleException.class, () -> CognitoDiscovery.issuer(runtime, POOL, 5), document);
        }
        body = " ".repeat(65_537);
        assertThrows(GradleException.class, () -> CognitoDiscovery.issuer(runtime, POOL, 5));
    }

    @Test void refusesRedirectsAndHttpErrorsWithoutFollowingThem() {
        body = "unavailable";
        for (int response : new int[] {302, 404, 500}) {
            status = response;
            var error = assertThrows(GradleException.class, () -> CognitoDiscovery.issuer(runtime, POOL, 5));
            assertTrue(error.getCause().getMessage().contains("HTTP " + response));
        }
        assertEquals(3, requests.size());
        assertTrue(requests.stream().noneMatch(path -> path.equals("/redirected")));
    }

    @Test void rejectsUntrustedTlsCertificates() throws Exception {
        var unrelated = TlsFixtures.identity(directory, "unrelated");
        TlsFixtures.trustStore(runtime.trustStore(), "PKCS12", "", unrelated);
        assertThrows(GradleException.class, () -> CognitoDiscovery.issuer(runtime, POOL, 5));
        assertTrue(requests.isEmpty());
    }

    @Test void retainsHostnameVerificationForTrustedCertificates() throws Exception {
        server.close();
        var wrongHost = TlsFixtures.identity(directory, "wrong-host", "dns:wrong.invalid");
        TlsFixtures.trustStore(runtime.trustStore(), "PKCS12", "", wrongHost);
        server = TlsFixtures.server(wrongHost);
        runtime = new EmulatorConnection(server.url().substring(0, server.url().length() - 1), "us-east-1", directory,
            directory.resolve("unused"), directory.resolve("unused.pem"));
        assertThrows(GradleException.class, () -> CognitoDiscovery.issuer(runtime, POOL, 5));
    }
}
