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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class CognitoTasksIntegrationTest {
    @TempDir Path project;

    @Test void provisionsReusesSeedsExportsAndDeletesDevelopmentResources() throws Exception {
        fixture();
        try {
            runner(project, "provision").build();
            JsonNode first = configuration();
            String pool = first.get("userPoolId").asText();
            String app = first.get("clientId").asText();
            assertEquals(first.get("endpoint").asText() + "/" + pool, first.get("issuer").asText());
            assertEquals(first.get("issuer").asText() + "/.well-known/jwks.json", first.get("jwksUri").asText());
            try (var client = new AwsClientFactory(connection(), 30).cognitoIdentityProvider()) {
                var auth = client.initiateAuth(r -> r.clientId(app).authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .authParameters(Map.of("USERNAME", "alice", "PASSWORD", "Local-Test123!"))).authenticationResult();
                assertNotNull(auth.accessToken());
                assertNotNull(auth.refreshToken());
                assertThrows(NotAuthorizedException.class, () -> client.initiateAuth(r -> r.clientId(app)
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH).authParameters(Map.of("USERNAME", "alice", "PASSWORD", "Wrong-Test123!"))));
                assertEquals("admins", client.adminListGroupsForUser(r -> r.userPoolId(pool).username("alice")).groups().get(0).groupName());
                String reuse = runner(project, "provision").build().getOutput();
                assertTrue(reuse.contains("Reusing configuration cache"), reuse);
                assertEquals(first, configuration());
                assertEquals(1, client.listUserPools(r -> r.maxResults(60)).userPools().size());
                assertEquals(1, client.listUserPoolClients(r -> r.userPoolId(pool).maxResults(60)).userPoolClients().size());

                String conflict = runner(project, "client", "-PconflictingFlows=true").buildAndFail().getOutput();
                assertTrue(conflict.contains("different authentication settings"), conflict);
                client.adminUpdateUserAttributes(r -> r.userPoolId(pool).username("alice")
                    .userAttributes(a -> a.name("email").value("changed@example.test")));
                String differs = runner(project, "users").buildAndFail().getOutput();
                assertTrue(differs.contains("overwrite=true"), differs);
                assertFalse(differs.contains("Local-Test123!"), differs);
                runner(project, "users", "-Poverwrite=true").build();
                assertEquals("alice@example.test", client.adminGetUser(r -> r.userPoolId(pool).username("alice")).userAttributes()
                    .stream().filter(a -> a.name().equals("email")).findFirst().orElseThrow().value());

                assertTrue(runner(project, "listCognitoUserPools").build().getOutput().contains("test-users\t" + pool));
                runner(project, "deleteCognitoUserPoolClient", "--userPoolName=test-users", "--clientName=test-app").build();
                runner(project, "deleteCognitoUserPoolClient", "--userPoolName=test-users", "--clientName=test-app").build();
                assertTrue(client.listUserPoolClients(r -> r.userPoolId(pool).maxResults(60)).userPoolClients().isEmpty());
                runner(project, "deleteCognitoUserPool", "--userPoolName=test-users").build();
                runner(project, "deleteCognitoUserPool", "--userPoolName=test-users").build();
                assertTrue(client.listUserPools(r -> r.maxResults(60)).userPools().isEmpty());
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    private JsonNode configuration() throws Exception { return new ObjectMapper().readTree(project.resolve("build/cognito.json").toFile()); }

    private EmulatorConnection connection() throws Exception {
        try (var dirs = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path dir = dirs.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(dir.getFileName().toString()), "us-east-1", dir);
        }
    }

    private void assertNoContainers() throws Exception {
        assertEquals("", new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking Cognito cleanup"));
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"cognito-integration\"\n");
        Files.writeString(project.resolve("users.json"), """
            [{"username":"alice","password":"Local-Test123!","attributes":{"email":"alice@example.test","email_verified":"true"},"groups":["admins"]}]
            """);
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.cognito.*
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { port.set(0) }
            tasks.withType<AbstractAwsResourceTask>().configureEach { mode.set(EmulatorMode.DEVELOPMENT) }
            val pool = tasks.register<CreateCognitoUserPoolTask>("pool") { userPoolName.set("test-users") }
            val client = tasks.register<CreateCognitoUserPoolClientTask>("client") {
                dependsOn(pool); userPoolName.set("test-users"); clientName.set("test-app")
                if (providers.gradleProperty("conflictingFlows").isPresent) explicitAuthFlows.set(listOf("ALLOW_USER_PASSWORD_AUTH"))
            }
            val users = tasks.register<SeedCognitoUsersTask>("users") {
                dependsOn(client); userPoolName.set("test-users"); fixtures.set(layout.projectDirectory.file("users.json"))
                overwrite.set(providers.gradleProperty("overwrite").map(String::toBoolean).orElse(false))
            }
            tasks.register<ExportCognitoConfigurationTask>("provision") {
                dependsOn(users); userPoolName.set("test-users"); clientName.set("test-app")
                outputFile.set(layout.buildDirectory.file("cognito.json"))
            }
            """);
    }
}
