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

package io.github.gregwhitaker.awsemulator.aws.cognito.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads user pool, app client, and group configuration without users, client secrets, or tokens. */
public final class CognitoReportCollector {
    public void collect(CognitoIdentityProviderClient client, ReportSection section) {
        section.read("ListUserPools", null, () -> pages(token -> {
            var page = client.listUserPools(ListUserPoolsRequest.builder().maxResults(60).nextToken(token).build());
            for (var pool : page.userPools()) {
                String id = pool.id();
                var details = section.resource("AWS::Cognito::UserPool", id);
                details.putAll(fields("name", pool.name(), "id", id, "status", pool.statusAsString(),
                    "createdAt", timestamp(pool.creationDate()), "modifiedAt", timestamp(pool.lastModifiedDate())));
                section.read("DescribeUserPool", id, () -> {
                    var metadata = client.describeUserPool(DescribeUserPoolRequest.builder().userPoolId(id).build()).userPool();
                    details.putAll(fields("arn", metadata.arn(), "mfaConfiguration", metadata.mfaConfigurationAsString(),
                        "deletionProtection", metadata.deletionProtectionAsString(), "estimatedNumberOfUsers", metadata.estimatedNumberOfUsers(),
                        "usernameAttributes", metadata.usernameAttributesAsStrings().stream().sorted().toList(),
                        "aliasAttributes", metadata.aliasAttributesAsStrings().stream().sorted().toList()));
                });
                clients(client, section, id, details);
                groups(client, section, id, details);
            }
            return page.nextToken();
        }));
    }

    private void clients(CognitoIdentityProviderClient client, ReportSection section, String pool, Map<String, Object> details) {
        var clients = new ArrayList<Map<String, Object>>();
        details.put("clients", clients);
        section.read("ListUserPoolClients", pool, () -> pages(token -> {
            var page = client.listUserPoolClients(ListUserPoolClientsRequest.builder().userPoolId(pool).maxResults(60).nextToken(token).build());
            for (var entry : page.userPoolClients()) {
                var configuration = fields("id", entry.clientId(), "name", entry.clientName());
                clients.add(configuration);
                section.read("DescribeUserPoolClient", pool + "/" + entry.clientId(), () -> {
                    var metadata = client.describeUserPoolClient(DescribeUserPoolClientRequest.builder().userPoolId(pool).clientId(entry.clientId()).build()).userPoolClient();
                    configuration.putAll(fields(
                        "explicitAuthFlows", metadata.explicitAuthFlowsAsStrings().stream().sorted().toList(),
                        "allowedOAuthFlows", metadata.allowedOAuthFlowsAsStrings().stream().sorted().toList(),
                        "allowedOAuthScopes", metadata.allowedOAuthScopes().stream().sorted().toList(),
                        "supportedIdentityProviders", metadata.supportedIdentityProviders().stream().sorted().toList(),
                        "accessTokenValidity", metadata.accessTokenValidity(), "idTokenValidity", metadata.idTokenValidity(),
                        "refreshTokenValidity", metadata.refreshTokenValidity(), "enableTokenRevocation", metadata.enableTokenRevocation()));
                    var units = metadata.tokenValidityUnits();
                    if (units != null) configuration.put("tokenValidityUnits", fields("accessToken", units.accessTokenAsString(),
                        "idToken", units.idTokenAsString(), "refreshToken", units.refreshTokenAsString()));
                });
            }
            return page.nextToken();
        }));
        clients.sort(Comparator.comparing(Object::toString));
    }

    private void groups(CognitoIdentityProviderClient client, ReportSection section, String pool, Map<String, Object> details) {
        var groups = new ArrayList<Map<String, Object>>();
        details.put("groups", groups);
        section.read("ListGroups", pool, () -> pages(token -> {
            var page = client.listGroups(ListGroupsRequest.builder().userPoolId(pool).limit(60).nextToken(token).build());
            for (var group : page.groups()) groups.add(fields("name", group.groupName(), "roleArn", group.roleArn(),
                "precedence", group.precedence(), "createdAt", timestamp(group.creationDate()),
                "modifiedAt", timestamp(group.lastModifiedDate())));
            return page.nextToken();
        }));
        groups.sort(Comparator.comparing(Object::toString));
    }
}
