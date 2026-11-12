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
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoUserFixturesTest {
    @TempDir Path directory;
    private final CognitoUserFixtures fixtures = new CognitoUserFixtures();
    private final CognitoUserFixtures.User user = new CognitoUserFixtures.User("alice", "Local-Test123!", Map.of("email", "alice@example.test"), List.of("admins"));

    @Test void validatesWholeDocumentAndOmitsValuesFromParseErrors() throws Exception {
        Path file = directory.resolve("users.json");
        Files.writeString(file, """
            [{"username":"alice","password":"Local-Test123!","attributes":{"email_verified":"true"},"groups":["admins"]}]
            """);
        assertEquals(List.of("admins"), CognitoUserFixtures.read(file).get(0).groups());
        assertFalse(user.toString().contains(user.password()));
        for (String bad : List.of(
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\",\"groups\":[1]}]",
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\",\"attributes\":{\"email_verified\":true}}]",
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\",\"attributes\":{\"sub\":\"immutable\"}}]",
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\"},{\"username\":\"a\",\"password\":\"Other-Test123!\"}]",
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\",\"password\":\"again\"}]",
            "[{\"username\":\"a\",\"password\":\"Local-Test123!\"}] trailing")) {
            Files.writeString(file, bad);
            var failure = assertThrows(GradleException.class, () -> CognitoUserFixtures.read(file));
            assertFalse(failure.getMessage().contains("Local-Test123!"));
            assertNull(failure.getCause());
        }
    }

    @Test void createsConfirmedUsersWithoutInvitationsAndAddsGroups() {
        var calls = new ArrayList<String>();
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> {
            calls.add(method.getName());
            return switch (method.getName()) {
                case "adminGetUser" -> throw UserNotFoundException.builder().build();
                case "adminCreateUser" -> {
                    var request = (AdminCreateUserRequest) args[0];
                    assertEquals(MessageActionType.SUPPRESS, request.messageAction());
                    assertEquals("alice@example.test", request.userAttributes().get(0).value());
                    yield AdminCreateUserResponse.builder().build();
                }
                case "adminSetUserPassword" -> {
                    var request = (AdminSetUserPasswordRequest) args[0];
                    assertTrue(request.permanent());
                    assertEquals(user.password(), request.password());
                    yield AdminSetUserPasswordResponse.builder().build();
                }
                case "getGroup" -> throw ResourceNotFoundException.builder().build();
                case "createGroup" -> CreateGroupResponse.builder().build();
                case "adminAddUserToGroup" -> AdminAddUserToGroupResponse.builder().build();
                default -> throw new AssertionError(method.getName());
            };
        });
        fixtures.seed(client, "pool", user, false);
        assertEquals(List.of("adminGetUser", "adminCreateUser", "adminSetUserPassword", "getGroup", "createGroup", "adminAddUserToGroup"), calls);
    }

    @Test void preservesExistingPasswordsAndRejectsConflictsUnlessOverwriteIsSelected() {
        var updates = new ArrayList<String>();
        var client = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "adminGetUser" -> AdminGetUserResponse.builder().enabled(true).userStatus(UserStatusType.CONFIRMED)
                .userAttributes(AttributeType.builder().name("email").value("alice@example.test").build()).build();
            case "getGroup" -> GetGroupResponse.builder().build();
            case "adminAddUserToGroup" -> AdminAddUserToGroupResponse.builder().build();
            case "adminUpdateUserAttributes" -> { updates.add("attributes"); yield AdminUpdateUserAttributesResponse.builder().build(); }
            case "adminSetUserPassword" -> { updates.add("password"); yield AdminSetUserPasswordResponse.builder().build(); }
            default -> throw new AssertionError(method.getName());
        });
        fixtures.seed(client, "pool", user, false);
        assertTrue(updates.isEmpty());
        var changed = new CognitoUserFixtures.User("alice", "Changed-Test123!", Map.of("email", "changed@example.test"), List.of());
        assertThrows(GradleException.class, () -> fixtures.seed(client, "pool", changed, false));
        assertTrue(updates.isEmpty());
        fixtures.seed(client, "pool", changed, true);
        assertEquals(List.of("attributes", "password"), updates);
    }

    @Test void rejectsDisabledUsersAndRedactsServiceExceptions() {
        var disabled = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> {
            assertEquals("adminGetUser", method.getName());
            return AdminGetUserResponse.builder().enabled(false).userStatus(UserStatusType.CONFIRMED).build();
        });
        assertThrows(GradleException.class, () -> fixtures.seed(disabled, "pool", user, true));
        var failure = SdkStub.client(CognitoIdentityProviderClient.class, (proxy, method, args) -> {
            throw InvalidPasswordException.builder().message(user.password()).build();
        });
        var exception = assertThrows(GradleException.class, () -> fixtures.seed(failure, "pool", user, false));
        assertTrue(exception.getMessage().contains("InvalidPasswordException"));
        assertFalse(exception.getMessage().contains(user.password()));
        assertNull(exception.getCause());
    }
}
