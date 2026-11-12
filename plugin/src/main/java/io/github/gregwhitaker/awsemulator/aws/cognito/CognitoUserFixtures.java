package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.JsonFixtures;
import org.gradle.api.GradleException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates complete fixtures before any SDK calls; never includes passwords or SDK excerpts in errors. */
final class CognitoUserFixtures {
    record User(String username, String password, Map<String, String> attributes, List<String> groups) {
        User {
            CognitoOperations.name(username, "fixture username");
            if (username.chars().anyMatch(Character::isWhitespace)) throw new GradleException("Fixture usernames must not contain whitespace.");
            if (password == null || password.isEmpty() || password.length() > 256 || password.chars().anyMatch(Character::isWhitespace)) {
                throw new GradleException("Fixture password must contain 1–256 non-whitespace characters (values omitted).");
            }
            for (var attribute : attributes.entrySet()) {
                if (attribute.getKey().isBlank() || attribute.getKey().equals("sub") || attribute.getValue().length() > 2048) {
                    throw new GradleException("Fixture attributes require nonblank names other than 'sub' and values of at most 2048 characters.");
                }
            }
            var unique = new HashSet<String>();
            for (String group : groups) {
                CognitoOperations.name(group, "fixture group");
                if (group.chars().anyMatch(Character::isWhitespace) || !unique.add(group)) {
                    throw new GradleException("Fixture groups must be unique and contain no whitespace.");
                }
            }
            attributes = Map.copyOf(attributes);
            groups = List.copyOf(groups);
        }

        @Override public String toString() { return "CognitoUser[values omitted]"; }
    }

    static List<User> read(Path path) {
        var result = new ArrayList<User>();
        var names = new HashSet<String>();
        for (var row : JsonFixtures.read(path, Set.of("username", "password", "attributes", "groups"))) {
            Map<String, String> attributes = new LinkedHashMap<>();
            if (row.has("attributes")) {
                if (!row.get("attributes").isObject()) throw new GradleException("Fixture attributes must be an object of string values.");
                row.get("attributes").fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isTextual()) throw new GradleException("Fixture attribute values must be strings.");
                    attributes.put(entry.getKey(), entry.getValue().textValue());
                });
            }
            var groups = new ArrayList<String>();
            if (row.has("groups")) {
                if (!row.get("groups").isArray()) throw new GradleException("Fixture groups must be an array of strings.");
                for (var group : row.get("groups")) {
                    if (!group.isTextual()) throw new GradleException("Fixture groups must be strings.");
                    groups.add(group.textValue());
                }
            }
            var user = new User(JsonFixtures.string(row, "username", null), JsonFixtures.string(row, "password", null), attributes, groups);
            if (!names.add(user.username())) throw new GradleException("Duplicate Cognito fixture username (values omitted).");
            result.add(user);
        }
        return List.copyOf(result);
    }

    void seed(CognitoIdentityProviderClient client, String poolId, User user, boolean overwrite) {
        try {
            AdminGetUserResponse existing;
            try { existing = client.adminGetUser(AdminGetUserRequest.builder().userPoolId(poolId).username(user.username()).build()); }
            catch (UserNotFoundException absent) { existing = null; }
            if (existing == null) {
                try {
                    client.adminCreateUser(AdminCreateUserRequest.builder().userPoolId(poolId).username(user.username())
                        .messageAction(MessageActionType.SUPPRESS).userAttributes(attributes(user)).build());
                } catch (UsernameExistsException concurrent) {
                    existing = client.adminGetUser(AdminGetUserRequest.builder().userPoolId(poolId).username(user.username()).build());
                }
            }
            if (existing != null) {
                if (Boolean.FALSE.equals(existing.enabled())) throw new GradleException("Existing Cognito fixture user is disabled; enable it explicitly (values omitted).");
                var actual = existing.userAttributes().stream().collect(Collectors.toMap(AttributeType::name, AttributeType::value));
                if (!overwrite && (existing.userStatus() != UserStatusType.CONFIRMED
                        || !user.attributes().entrySet().stream().allMatch(entry -> entry.getValue().equals(actual.get(entry.getKey()))))) {
                    throw new GradleException("Existing Cognito fixture user differs; set overwrite=true to replace supplied attributes and password (values omitted).");
                }
                if (overwrite && !user.attributes().isEmpty()) {
                    client.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder().userPoolId(poolId)
                        .username(user.username()).userAttributes(attributes(user)).build());
                }
            }
            if (existing == null || overwrite) {
                client.adminSetUserPassword(AdminSetUserPasswordRequest.builder().userPoolId(poolId).username(user.username())
                    .password(user.password()).permanent(true).build());
            }
            for (String group : user.groups()) {
                try { client.getGroup(GetGroupRequest.builder().userPoolId(poolId).groupName(group).build()); }
                catch (ResourceNotFoundException absent) {
                    try { client.createGroup(CreateGroupRequest.builder().userPoolId(poolId).groupName(group).build()); }
                    catch (GroupExistsException concurrent) { /* Reuse the concurrently created group. */ }
                }
                client.adminAddUserToGroup(AdminAddUserToGroupRequest.builder().userPoolId(poolId)
                    .username(user.username()).groupName(group).build());
            }
        } catch (SdkException e) {
            // SDK exception text can contain request/fixture values; intentionally omit it and its cause.
            throw new GradleException("Cognito user seeding failed (" + e.getClass().getSimpleName() + "; values omitted).");
        }
    }

    private static List<AttributeType> attributes(User user) {
        return user.attributes().entrySet().stream().map(entry -> AttributeType.builder().name(entry.getKey()).value(entry.getValue()).build()).toList();
    }
}
