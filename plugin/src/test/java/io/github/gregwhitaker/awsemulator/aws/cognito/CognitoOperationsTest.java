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

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CognitoOperationsTest {
    private final CognitoOperations operations = new CognitoOperations();

    @Test void resolvesExactNamesAcrossAllPagesAndRejectsAmbiguousNames() {
        var ambiguous = new AtomicBoolean();
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> {
            if (!method.getName().equals("listUserPools")) throw new AssertionError(method.getName());
            var request = (ListUserPoolsRequest) args[0];
            assertEquals(60, request.maxResults());
            if (request.nextToken() == null) return ListUserPoolsResponse.builder().nextToken("next")
                .userPools(pool(ambiguous.get() ? "app" : "app-other", "first")).build();
            assertEquals("next", request.nextToken());
            return ListUserPoolsResponse.builder().userPools(pool("app", "second")).build();
        });
        assertEquals("second", operations.requirePool(client, "app"));
        ambiguous.set(true);
        assertThrows(GradleException.class, () -> operations.createPool(client, "app"));
        assertThrows(GradleException.class, () -> operations.deletePool(client, "app"));
    }

    @Test void createsMissingPoolAndReusesItWithoutMutation() {
        var created = new AtomicBoolean();
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listUserPools" -> ListUserPoolsResponse.builder().userPools(created.get() ? List.of(pool("app", "pool-id")) : List.of()).build();
            case "createUserPool" -> {
                assertFalse(created.getAndSet(true));
                assertEquals("app", ((CreateUserPoolRequest) args[0]).poolName());
                yield CreateUserPoolResponse.builder().userPool(UserPoolType.builder().id("pool-id").build()).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertEquals("pool-id", operations.createPool(client, "app"));
        assertEquals("pool-id", operations.createPool(client, "app"));
    }

    @Test void createsPublicClientAndChecksExistingSettingsWithoutUpdating() {
        var created = new AtomicBoolean();
        var secret = new AtomicBoolean();
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listUserPoolClients" -> ListUserPoolClientsResponse.builder().userPoolClients(created.get()
                ? List.of(UserPoolClientDescription.builder().clientName("web").clientId("generated").build()) : List.of()).build();
            case "createUserPoolClient" -> {
                var request = (CreateUserPoolClientRequest) args[0];
                assertEquals("pool-id", request.userPoolId());
                assertEquals("web", request.clientName());
                assertFalse(request.generateSecret());
                assertEquals(CognitoOperations.DEFAULT_AUTH_FLOWS, request.explicitAuthFlowsAsStrings());
                assertFalse(created.getAndSet(true));
                yield CreateUserPoolClientResponse.builder().userPoolClient(UserPoolClientType.builder().clientId("generated").build()).build();
            }
            case "describeUserPoolClient" -> DescribeUserPoolClientResponse.builder().userPoolClient(UserPoolClientType.builder()
                .explicitAuthFlowsWithStrings(CognitoOperations.DEFAULT_AUTH_FLOWS).clientSecret(secret.get() ? "secret" : null).build()).build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        assertEquals("generated", operations.createClient(client, "pool-id", "web", CognitoOperations.DEFAULT_AUTH_FLOWS));
        assertEquals("generated", operations.createClient(client, "pool-id", "web", CognitoOperations.DEFAULT_AUTH_FLOWS));
        assertThrows(GradleException.class, () -> operations.createClient(client, "pool-id", "web", List.of("ALLOW_USER_PASSWORD_AUTH")));
        secret.set(true);
        assertThrows(GradleException.class, () -> operations.createClient(client, "pool-id", "web", CognitoOperations.DEFAULT_AUTH_FLOWS));
    }

    @Test void rejectsDuplicateClientNamesAndRepeatedPaginationTokens() {
        var duplicate = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> ListUserPoolClientsResponse.builder()
            .userPoolClients(UserPoolClientDescription.builder().clientName("web").clientId("one").build(),
                UserPoolClientDescription.builder().clientName("web").clientId("two").build()).build());
        assertThrows(GradleException.class, () -> operations.requireClient(duplicate, "pool", "web"));
        var repeating = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listUserPools" -> ListUserPoolsResponse.builder().nextToken("same").build();
            case "listUserPoolClients" -> ListUserPoolClientsResponse.builder().nextToken("same").build();
            default -> throw new AssertionError(method.getName());
        });
        assertThrows(GradleException.class, () -> operations.pools(repeating));
        assertThrows(GradleException.class, () -> operations.clients(repeating, "pool"));
    }

    @Test void deletionIsIdempotentButUnexpectedErrorsPropagate() {
        var missing = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> {
            assertEquals("listUserPools", method.getName());
            return ListUserPoolsResponse.builder().build();
        });
        operations.deletePool(missing, "app");
        operations.deleteClient(missing, "app", "web");
        assertThrows(GradleException.class, () -> operations.requirePool(missing, "app"));
        var failure = NotAuthorizedException.builder().message("denied").build();
        var denied = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listUserPools" -> ListUserPoolsResponse.builder().userPools(pool("app", "id")).build();
            case "deleteUserPool" -> throw failure;
            default -> throw new AssertionError(method.getName());
        });
        assertSame(failure, assertThrows(NotAuthorizedException.class, () -> operations.deletePool(denied, "app")));
    }

    private static UserPoolDescriptionType pool(String name, String id) {
        return UserPoolDescriptionType.builder().name(name).id(id).build();
    }
}
