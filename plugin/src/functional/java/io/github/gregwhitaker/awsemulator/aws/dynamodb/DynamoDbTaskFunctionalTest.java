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

package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbTaskFunctionalTest {
    @TempDir Path project;

    @Test
    void registersTheServiceGroupAndDevelopmentCommandsWithoutStartingDocker() throws Exception {
        fixture("""
            val create = tasks.register<CreateDynamoDbTableTask>("createProducts") {
                tableName.set("catalog.products")
                keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                attributeDefinitions.add(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
            }
            check(create.get().mode.get() == EmulatorMode.MANAGED)
            check(create.get().provisionedThroughput.get().readCapacityUnits() == 100L)
            check(create.get().provisionedThroughput.get().writeCapacityUnits() == 100L)
            check(create.get().sseSpecification.get().enabled())
            check(create.get().waitTimeoutSeconds.get() == 60)
            check(tasks.named<ListDynamoDBTablesTask>("listDynamoDbTables").get().mode.get() == EmulatorMode.DEVELOPMENT)
            check(tasks.named<DeleteDynamoDbTableTask>("deleteDynamoDbTable").get().mode.get() == EmulatorMode.DEVELOPMENT)
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - DynamoDB tasks"), output);
        assertTrue(output.contains("createProducts - Creates or reuses a DynamoDB table"), output);
        assertTrue(output.contains("listDynamoDbTables - Lists DynamoDB tables"), output);
        assertTrue(output.contains("deleteDynamoDbTable - Deletes a DynamoDB table"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        String help = runner(project, "help", "--task", "deleteDynamoDbTable").build().getOutput();
        assertTrue(help.contains("--tableName"), help);
        assertTrue(help.contains("--mode"), help);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void validatesSdkModelInputsBeforeDockerAndReusesTheirCachedConfiguration() throws Exception {
        fixture("""
            tasks.register<CreateDynamoDbTableTask>("badSchema") {
                tableName.set("catalog.products")
                keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                attributeDefinitions.add(AttributeDefinition.builder().attributeName("wrong").attributeType(ScalarAttributeType.S).build())
                streamSpecification.set(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build())
            }
            tasks.register<DeleteDynamoDbTableTask>("badWait") { tableName.set("catalog.products"); waitTimeoutSeconds.set(0) }
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "badSchema", "badWait", "deleteDynamoDbTable", "--tableName=bad/name", "--continue").buildAndFail().getOutput();
            assertTrue(output.contains("Missing attribute definition for key 'id'"), output);
            assertTrue(output.contains("waitTimeoutSeconds must be positive"), output);
            assertTrue(output.contains("tableName must contain"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void resolvesAndValidatesBuildSrcInitializersBeforeConnecting() throws Exception {
        Path buildSrc = project.resolve("buildSrc");
        Files.createDirectories(buildSrc.resolve("src/main/java/fixture"));
        Files.writeString(buildSrc.resolve("settings.gradle.kts"), "rootProject.name = \"buildSrc\"\n");
        Files.writeString(buildSrc.resolve("build.gradle.kts"), """
            plugins { java }
            repositories { mavenCentral() }
            dependencies { implementation(files(%s)) }
            """.formatted(pluginClasspath()));
        Files.writeString(buildSrc.resolve("src/main/java/fixture/ValidInitializer.java"), """
            package fixture;
            import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
            public class ValidInitializer {
                public ValidInitializer(DynamoDbClient client) {}
                public void run() {}
            }
            """);
        Files.writeString(buildSrc.resolve("src/main/java/fixture/InvalidInitializer.java"), """
            package fixture;
            public class InvalidInitializer {
                public InvalidInitializer(String wrong) {}
                public void run() {}
            }
            """);
        fixture("""
            listOf("validInitializer", "invalidInitializer").forEach { taskName ->
                tasks.register<CreateDynamoDbTableTask>(taskName) {
                    tableName.set("catalog.products")
                    keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                    attributeDefinitions.add(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                    initializer.set(if (taskName == "validInitializer") "fixture.ValidInitializer" else "fixture.InvalidInitializer")
                    // A deliberate timeout error proves initializer resolution succeeded without needing Docker.
                    apiCallTimeoutSeconds.set(0)
                }
            }
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "validInitializer", "invalidInitializer", "--continue").buildAndFail().getOutput();
            assertTrue(output.contains("apiCallTimeoutSeconds must be positive"), output);
            assertTrue(output.contains("requires a public constructor accepting DynamoDbClient"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private String pluginClasspath() {
        // Export the plugin and its SDK together, as a buildSrc dependency on the plugin would.
        return String.join(", ", runner(project).getPluginClasspath().stream().map(file -> "\""
            + file.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"").toList());
    }

    private void fixture(String tasks) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"dynamodb-fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.dynamodb.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import software.amazon.awssdk.services.dynamodb.model.*
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            """ + tasks);
    }
}
