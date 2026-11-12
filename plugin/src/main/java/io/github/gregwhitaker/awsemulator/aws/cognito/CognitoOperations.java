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

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves service-generated IDs by exact name, rejecting ambiguous resources. */
final class CognitoOperations {
    static final List<String> DEFAULT_AUTH_FLOWS = List.of(
        "ALLOW_USER_PASSWORD_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH");
    private static final Set<String> AUTH_FLOWS = Set.copyOf(DEFAULT_AUTH_FLOWS);

    static String name(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new GradleException(label + " must contain 1–128 characters and not be blank.");
        }
        return value;
    }

    static List<String> authFlows(List<String> flows) {
        if (flows.isEmpty() || !AUTH_FLOWS.containsAll(flows) || new HashSet<>(flows).size() != flows.size()) {
            throw new GradleException("explicitAuthFlows must be a nonempty, unique list of " + DEFAULT_AUTH_FLOWS + ".");
        }
        return List.copyOf(flows);
    }

    List<UserPoolDescriptionType> pools(CognitoIdentityProviderClient client) {
        var result = new ArrayList<UserPoolDescriptionType>();
        var seen = new HashSet<String>();
        String token = null;
        do {
            var page = client.listUserPools(ListUserPoolsRequest.builder().maxResults(60).nextToken(token).build());
            result.addAll(page.userPools());
            token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    String findPool(CognitoIdentityProviderClient client, String name) {
        var matches = pools(client).stream().filter(pool -> name.equals(pool.name())).toList();
        if (matches.size() > 1) throw new GradleException("Multiple Cognito user pools have the name '" + name + "'; select a unique name.");
        return matches.isEmpty() ? null : requiredId(matches.get(0).id(), "user pool");
    }

    String requirePool(CognitoIdentityProviderClient client, String name) {
        String id = findPool(client, name);
        if (id == null) throw new GradleException("Cognito user pool '" + name + "' does not exist.");
        return id;
    }

    String createPool(CognitoIdentityProviderClient client, String name) {
        String existing = findPool(client, name);
        if (existing != null) return existing;
        var created = client.createUserPool(CreateUserPoolRequest.builder().poolName(name).build()).userPool();
        String id = requiredId(created == null ? null : created.id(), "user pool");
        // Names are not unique in Cognito. Detect an ambiguous concurrent creation, never adopt an arbitrary pool.
        if (!id.equals(requirePool(client, name))) throw new GradleException("Cognito user pool changed during creation; retry after resolving the name conflict.");
        return id;
    }

    List<UserPoolClientDescription> clients(CognitoIdentityProviderClient client, String poolId) {
        var result = new ArrayList<UserPoolClientDescription>();
        var seen = new HashSet<String>();
        String token = null;
        do {
            var page = client.listUserPoolClients(ListUserPoolClientsRequest.builder()
                .userPoolId(poolId).maxResults(60).nextToken(token).build());
            result.addAll(page.userPoolClients());
            token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    String findClient(CognitoIdentityProviderClient client, String poolId, String name) {
        var matches = clients(client, poolId).stream().filter(app -> name.equals(app.clientName())).toList();
        if (matches.size() > 1) throw new GradleException("Multiple Cognito app clients have the name '" + name + "' in the selected pool; select a unique name.");
        return matches.isEmpty() ? null : requiredId(matches.get(0).clientId(), "app client");
    }

    String requireClient(CognitoIdentityProviderClient client, String poolId, String name) {
        String id = findClient(client, poolId, name);
        if (id == null) throw new GradleException("Cognito app client '" + name + "' does not exist in the selected pool.");
        return id;
    }

    String createClient(CognitoIdentityProviderClient client, String poolId, String name, List<String> flows) {
        String id = findClient(client, poolId, name);
        if (id == null) {
            var created = client.createUserPoolClient(CreateUserPoolClientRequest.builder()
                .userPoolId(poolId).clientName(name).generateSecret(false).explicitAuthFlowsWithStrings(flows).build()).userPoolClient();
            id = requiredId(created == null ? null : created.clientId(), "app client");
            if (!id.equals(requireClient(client, poolId, name))) throw new GradleException("Cognito app client changed during creation; retry after resolving the name conflict.");
        }
        var actual = client.describeUserPoolClient(DescribeUserPoolClientRequest.builder().userPoolId(poolId).clientId(id).build()).userPoolClient();
        if (actual == null || (actual.clientSecret() != null && !actual.clientSecret().isEmpty())
                || !new HashSet<>(flows).equals(new HashSet<>(actual.explicitAuthFlowsAsStrings()))) {
            throw new GradleException("Cognito app client '" + name + "' has different authentication settings. Existing clients are not modified; choose another name or delete the client explicitly.");
        }
        return id;
    }

    void deletePool(CognitoIdentityProviderClient client, String name) {
        String id = findPool(client, name);
        if (id == null) return;
        try { client.deleteUserPool(DeleteUserPoolRequest.builder().userPoolId(id).build()); }
        catch (ResourceNotFoundException ignored) { /* Concurrently removed. */ }
    }

    void deleteClient(CognitoIdentityProviderClient client, String poolName, String name) {
        String poolId = findPool(client, poolName);
        if (poolId == null) return;
        try {
            String id = findClient(client, poolId, name);
            if (id != null) client.deleteUserPoolClient(DeleteUserPoolClientRequest.builder().userPoolId(poolId).clientId(id).build());
        } catch (ResourceNotFoundException ignored) { /* Pool or client concurrently removed. */ }
    }

    private static String requiredId(String id, String resource) {
        if (id == null || id.isBlank()) throw new GradleException("Cognito did not return a " + resource + " ID.");
        return id;
    }
}
