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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CognitoReportCollectorTest {
    @Test void followsPoolClientAndGroupPagesWithoutUsersOrClientSecrets() {
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (p, m, a) -> switch (m.getName()) {
            case "listUserPools" -> {
                String token = ((ListUserPoolsRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("pools"));
                yield ListUserPoolsResponse.builder().userPools(UserPoolDescriptionType.builder().id(token == null ? "one" : "two").name("users").build())
                    .nextToken(token == null ? "pools" : null).build();
            }
            case "describeUserPool" -> DescribeUserPoolResponse.builder().userPool(UserPoolType.builder().arn("arn:pool")
                .mfaConfiguration(UserPoolMfaType.OPTIONAL).estimatedNumberOfUsers(5)
                .emailVerificationMessage("private-message").userPoolTags(Map.of("private-tag", "private-value")).build()).build();
            case "listUserPoolClients" -> {
                var request = (ListUserPoolClientsRequest) a[0];
                assertTrue(List.of("one", "two").contains(request.userPoolId()));
                assertTrue(request.nextToken() == null || request.nextToken().equals("clients"));
                yield ListUserPoolClientsResponse.builder().userPoolClients(UserPoolClientDescription.builder()
                    .clientId(request.nextToken() == null ? "a" : "b").clientName("app").build())
                    .nextToken(request.nextToken() == null ? "clients" : null).build();
            }
            case "describeUserPoolClient" -> {
                var request = (DescribeUserPoolClientRequest) a[0];
                assertTrue(List.of("one", "two").contains(request.userPoolId()));
                assertTrue(List.of("a", "b").contains(request.clientId()));
                yield DescribeUserPoolClientResponse.builder().userPoolClient(UserPoolClientType.builder().clientSecret("private-client-secret")
                    .callbackURLs("https://user:private-password@example.test").logoutURLs("private-logout")
                    .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH).allowedOAuthScopes("openid")
                    .accessTokenValidity(1).tokenValidityUnits(TokenValidityUnitsType.builder().accessToken(TimeUnitsType.HOURS).build()).build()).build();
            }
            case "listGroups" -> {
                var request = (ListGroupsRequest) a[0];
                assertTrue(List.of("one", "two").contains(request.userPoolId()));
                assertTrue(request.nextToken() == null || request.nextToken().equals("groups"));
                yield ListGroupsResponse.builder().groups(GroupType.builder().groupName(request.nextToken() == null ? "admins" : "users")
                    .precedence(1).roleArn("arn:role").description("private-description").build())
                    .nextToken(request.nextToken() == null ? "groups" : null).build();
            }
            default -> throw new AssertionError("Unexpected or user/token call: " + m.getName());
        });
        var section = new ReportSection("Cognito");
        new CognitoReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        var pool = section.result().resources().get(0).configuration();
        assertEquals("OPTIONAL", pool.get("mfaConfiguration"));
        assertEquals(5, pool.get("estimatedNumberOfUsers"));
        assertEquals(2, ((List<?>) pool.get("clients")).size());
        assertEquals(2, ((List<?>) pool.get("groups")).size());
        assertTrue(pool.toString().contains("ALLOW_USER_PASSWORD_AUTH"));
        assertTrue(pool.toString().contains("hours"));
        assertFalse(section.result().toString().contains("private-"));
    }

    @Test void failedPoolAndClientDescriptionsDoNotHideGroupMetadata() {
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (p, m, a) -> switch (m.getName()) {
            case "listUserPools" -> ListUserPoolsResponse.builder().userPools(UserPoolDescriptionType.builder().id("pool").name("users").build()).build();
            case "describeUserPool", "describeUserPoolClient" -> throw NotAuthorizedException.builder().message("private-error").build();
            case "listUserPoolClients" -> ListUserPoolClientsResponse.builder().userPoolClients(UserPoolClientDescription.builder().clientId("client").build()).build();
            case "listGroups" -> ListGroupsResponse.builder().groups(GroupType.builder().groupName("retained").build()).build();
            default -> throw new AssertionError(m.getName());
        });
        var section = new ReportSection("Cognito");
        new CognitoReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(2, section.result().issues().size());
        assertTrue(section.result().resources().toString().contains("retained"));
        assertFalse(section.result().toString().contains("private-error"));
    }
}
