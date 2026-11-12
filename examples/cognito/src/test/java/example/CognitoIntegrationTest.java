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

package example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoIntegrationTest {
    @Test
    void signsInAndValidatesSignedTokensOverTrustedHttps() throws Exception {
        var config = LocalCognitoSecurity.CognitoConfiguration.read();
        try (var client = CognitoIdentityProviderClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var alice = signIn(client, config.clientId(), "alice");
            var bob = signIn(client, config.clientId(), "bob");
            assertThrows(NotAuthorizedException.class, () -> client.initiateAuth(r -> r.clientId(config.clientId())
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH).authParameters(Map.of("USERNAME", "alice", "PASSWORD", "Wrong-Test123!"))));
            var refreshed = client.initiateAuth(r -> r.clientId(config.clientId()).authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                .authParameters(Map.of("REFRESH_TOKEN", alice.refreshToken()))).authenticationResult();
            String otherClient = client.createUserPoolClient(r -> r.userPoolId(config.userPoolId()).clientName("other-client")
                .generateSecret(false).explicitAuthFlowsWithStrings("ALLOW_USER_PASSWORD_AUTH")).userPoolClient().clientId();
            var other = signIn(client, otherClient, "alice");
            var application = new SpringApplication(CognitoApplication.class);
            application.setDefaultProperties(Map.of("server.port", "0", "spring.profiles.active", "local"));
            try (var context = application.run()) {
                String endpoint = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
                assertEquals(401, get(endpoint + "/me", null).statusCode());
                var me = get(endpoint + "/me", alice.accessToken());
                assertEquals(200, me.statusCode(), me.body());
                assertTrue(me.body().contains("alice"), me.body());
                assertEquals(200, get(endpoint + "/admin", alice.accessToken()).statusCode());
                assertEquals(403, get(endpoint + "/admin", bob.accessToken()).statusCode());
                assertEquals(200, get(endpoint + "/me", refreshed.accessToken()).statusCode());
                assertEquals(401, get(endpoint + "/me", alice.idToken()).statusCode());
                assertEquals(401, get(endpoint + "/me", other.accessToken()).statusCode());
                String tampered = alice.accessToken().substring(0, alice.accessToken().length() - 10) + "AAAAAAAAAA";
                assertEquals(401, get(endpoint + "/me", tampered).statusCode());
                assertFalse(Boolean.getBoolean("example.fail"), "Intentional example failure to verify managed cleanup");
            }
        }
    }

    private static AuthenticationResultType signIn(CognitoIdentityProviderClient client, String app, String username) {
        return client.initiateAuth(r -> r.clientId(app).authFlow(AuthFlowType.USER_PASSWORD_AUTH)
            .authParameters(Map.of("USERNAME", username, "PASSWORD", "Local-Test123!"))).authenticationResult();
    }

    private static HttpResponse<String> get(String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
