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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbTasksIntegrationTest {
    @TempDir Path project;

    @Test
    void createsInitializesReusesListsAndDeletesADevelopmentTable() throws Exception {
        fixture();
        try {
            String created = isolated("createProducts").build().getOutput();
            assertTrue(created.contains("Initialized DynamoDB table: catalog.products"), created);
            try (var client = new AwsClientFactory(developmentConnection(), 30).dynamoDb()) {
                var description = client.describeTable(r -> r.tableName("catalog.products")).table();
                assertEquals(TableStatus.ACTIVE, description.tableStatus());
                assertEquals(100L, description.provisionedThroughput().readCapacityUnits());
                assertEquals(100L, description.provisionedThroughput().writeCapacityUnits());
                assertEquals("by-category", description.globalSecondaryIndexes().get(0).indexName());
                assertEquals("by-price", description.localSecondaryIndexes().get(0).indexName());
                assertTrue(description.streamSpecification().streamEnabled());
                assertEquals(StreamViewType.NEW_AND_OLD_IMAGES, description.streamSpecification().streamViewType());
                assertNotNull(description.latestStreamArn());
                var key = Map.of("id", text("seed"), "created", text("2026"));
                assertEquals("initial", client.getItem(r -> r.tableName("catalog.products").key(key).consistentRead(true)).item().get("value").s());
                assertEquals(1, client.query(r -> r.tableName("catalog.products").indexName("by-category")
                    .keyConditionExpression("category = :category").expressionAttributeValues(Map.of(":category", text("books")))).count());
                client.putItem(r -> r.tableName("catalog.products").item(Map.of("id", text("seed"), "created", text("2026"), "value", text("keep"))));
                String reused = isolated("createProducts").build().getOutput();
                assertTrue(reused.contains("Reusing configuration cache"), reused);
                assertTrue(reused.contains("catalog.products (reused)"), reused);
                assertFalse(reused.contains("Initialized DynamoDB table"), reused);
                assertEquals("keep", client.getItem(r -> r.tableName("catalog.products").key(key).consistentRead(true)).item().get("value").s());

                String listed = isolated("listDynamoDbTables").build().getOutput();
                assertTrue(listed.contains("catalog.products"), listed);
                isolated("deleteDynamoDbTable", "--tableName=catalog.products").build();
                assertThrows(ResourceNotFoundException.class, () -> client.describeTable(r -> r.tableName("catalog.products")));
                assertTrue(isolated("listDynamoDbTables").build().getOutput().contains("No Tables Found!"));
                String missing = isolated("deleteDynamoDbTable", "--tableName=catalog.products").buildAndFail().getOutput();
                assertTrue(missing.contains("DynamoDB table does not exist: catalog.products"), missing);
            }
            assertFalse(containers().isBlank());
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test
    void initializedTablesAreSharedWithTestsAndFreshOnConfigurationCacheReuse() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = isolated("test", "listDynamoDbTables", "--mode=MANAGED", "-Pmanaged=true").build().getOutput();
            assertTrue(output.contains("Initialized DynamoDB table: catalog.products"), output);
            assertTrue(output.contains("catalog.products (reused)"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertManagedCleanup();
        }
    }

    @Test
    void anInitializerFailureFailsTheBuildAndCleansUpTheManagedRuntime() throws Exception {
        fixture();
        String output = isolated("failedInitializer", "-Pmanaged=true").buildAndFail().getOutput();
        assertTrue(output.contains("DynamoDB initializer failed: fixture.FailingInitializer"), output);
        assertTrue(output.contains("intentional initializer failure"), output);
        assertManagedCleanup();
    }

    private AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }

    private GradleRunner isolated(String... tasks) {
        var environment = new HashMap<>(System.getenv());
        environment.put("AWS_ACCESS_KEY_ID", "not-real");
        environment.put("AWS_SECRET_ACCESS_KEY", "not-real");
        environment.put("AWS_PROFILE", "nonexistent-profile");
        environment.put("AWS_ENDPOINT_URL", "https://127.0.0.1:1");
        environment.put("AWS_ENDPOINT_URL_DYNAMODB", "https://127.0.0.1:1");
        return runner(project, tasks).withEnvironment(environment);
    }

    private EmulatorConnection developmentConnection() throws Exception {
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
    }

    private void assertManagedCleanup() throws Exception {
        assertEquals("", containers());
        try (var files = Files.list(project.resolve(".aws-emulator"))) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking DynamoDB task cleanup");
    }

    private String pluginClasspath() {
        // Export the plugin and its SDK together, as a buildSrc dependency on the plugin would.
        return String.join(", ", runner(project).getPluginClasspath().stream().map(file -> "\""
            + file.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"").toList());
    }

    private void fixture() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"dynamodb-fixture\"\n");
        write("buildSrc/settings.gradle.kts", "rootProject.name = \"buildSrc\"\n");
        write("buildSrc/build.gradle.kts", """
            plugins { java }
            repositories { mavenCentral() }
            dependencies { implementation(files(%s)) }
            """.formatted(pluginClasspath()));
        write("buildSrc/src/main/java/fixture/SeedProducts.java", """
            package fixture;
            import java.util.Map;
            import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
            import software.amazon.awssdk.services.dynamodb.model.*;
            public class SeedProducts {
                private final DynamoDbClient client;
                public SeedProducts(DynamoDbClient client) { this.client = client; }
                public void run() {
                    if (client.describeTable(r -> r.tableName("catalog.products")).table().tableStatus() != TableStatus.ACTIVE)
                        throw new IllegalStateException("Initializer ran before ACTIVE");
                    client.putItem(r -> r.tableName("catalog.products").item(Map.of(
                        "id", text("seed"), "created", text("2026"), "category", text("books"),
                        "price", AttributeValue.builder().n("12.5").build(), "value", text("initial"))));
                }
                private AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
            }
            """);
        write("buildSrc/src/main/java/fixture/FailingInitializer.java", """
            package fixture;
            import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
            public class FailingInitializer {
                public FailingInitializer(DynamoDbClient client) {}
                public void run() { throw new IllegalStateException("intentional initializer failure"); }
            }
            """);
        write("build.gradle.kts", """
            import io.github.gregwhitaker.awsemulator.aws.dynamodb.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import software.amazon.awssdk.services.dynamodb.model.*
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            repositories { mavenCentral() }
            dependencies {
                implementation(platform("software.amazon.awssdk:bom:2.39.6"))
                implementation("software.amazon.awssdk:dynamodb")
                implementation("software.amazon.awssdk:url-connection-client")
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            val runtimeMode = if (providers.gradleProperty("managed").getOrElse("false").toBoolean())
                EmulatorMode.MANAGED else EmulatorMode.DEVELOPMENT
            tasks.withType<CreateDynamoDbTableTask>().configureEach {
                mode.set(runtimeMode)
                tableName.set("catalog.products")
                keySchema.set(listOf(
                    KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("created").keyType(KeyType.RANGE).build()))
                attributeDefinitions.set(listOf("id", "created", "category").map {
                    AttributeDefinition.builder().attributeName(it).attributeType(ScalarAttributeType.S).build()
                } + AttributeDefinition.builder().attributeName("price").attributeType(ScalarAttributeType.N).build())
                globalSecondaryIndexes.add(GlobalSecondaryIndex.builder().indexName("by-category")
                    .keySchema(KeySchemaElement.builder().attributeName("category").keyType(KeyType.HASH).build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(2L).writeCapacityUnits(2L).build()).build())
                localSecondaryIndexes.add(LocalSecondaryIndex.builder().indexName("by-price")
                    .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("price").keyType(KeyType.RANGE).build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build())
                streamSpecification.set(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES).build())
                initializer.set("fixture.SeedProducts")
            }
            val create = tasks.register<CreateDynamoDbTableTask>("createProducts")
            val modify = tasks.register<JavaExec>("modifyProducts") {
                dependsOn(create)
                classpath = sourceSets.main.get().runtimeClasspath
                mainClass.set("Modify")
            }
            val repeat = tasks.register<CreateDynamoDbTableTask>("repeatProducts") { dependsOn(modify) }
            tasks.register<CreateDynamoDbTableTask>("failedInitializer") {
                tableName.set("failed-table")
                initializer.set("fixture.FailingInitializer")
            }
            val tests = tasks.named<Test>("test") { dependsOn(repeat); useJUnitPlatform() }
            awsEmulator { port.set(0); useWith(modify); useWith(tests) }
            """);
        write("src/main/java/Modify.java", """
            import java.util.Map;
            import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
            import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            public class Modify {
                public static void main(String[] args) {
                    try (var client = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        var key = Map.of("id", text("seed"), "created", text("2026"));
                        if (!client.getItem(r -> r.tableName("catalog.products").key(key).consistentRead(true)).item().get("value").s().equals("initial"))
                            throw new AssertionError("Each managed build must initialize a fresh table");
                        client.putItem(r -> r.tableName("catalog.products").item(Map.of("id", text("seed"), "created", text("2026"), "value", text("keep"))));
                    }
                }
                private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
            }
            """);
        write("src/test/java/ProductsTest.java", """
            import java.util.Map;
            import org.junit.jupiter.api.Test;
            import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
            import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            import static org.junit.jupiter.api.Assertions.*;
            class ProductsTest {
                @Test void reusingTheTablePreservesDataAndDoesNotRerunTheInitializer() {
                    try (var client = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        var key = Map.of("id", AttributeValue.builder().s("seed").build(), "created", AttributeValue.builder().s("2026").build());
                        assertEquals("keep", client.getItem(r -> r.tableName("catalog.products").key(key).consistentRead(true)).item().get("value").s());
                    }
                }
            }
            """);
    }

    private void write(String name, String contents) throws Exception {
        Path file = project.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
