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

package io.github.gregwhitaker.awsemulator.aws;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class DataScenariosIntegrationTest {
    @TempDir Path project;

    @Test void selectiveResetAndExportReplayPreserveRuntimeAndUnrelatedResources() throws Exception {
        fixture();
        try {
            runner(project, "seedAwsEmulator").build();
            var clients = new AwsClientFactory(connection(), 30);
            String endpoint = connection().endpoint().toString();
            String container = containers();
            try (var s3 = clients.s3(); var sqs = clients.sqs()) {
                s3.createBucket(r -> r.bucket("unrelated-assets"));
                s3.putObject(r -> r.bucket("unrelated-assets").key("keep"), RequestBody.fromString("unrelated-payload"));
                sqs.createQueue(r -> r.queueName("unrelated-queue"));
            }
            for (int run = 0; run < 2; run++) {
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
                    s3.putObject(r -> r.bucket("scenario-assets").key("captured.bin").contentType("application/custom")
                        .metadata(Map.of("fixture", "captured")), RequestBody.fromBytes(new byte[] {0, 1, -1}));
                    dynamo.putItem(r -> r.tableName("scenario-products").item(Map.of("id", text("captured"), "body", text("captured-value"))));
                }
                String exported = runner(project, "exportScenario").build().getOutput();
                if (run > 0) assertTrue(exported.contains("Reusing configuration cache"), exported);
                assertTrue(exported.contains("Exported 3 S3 objects"), exported);
                assertTrue(exported.contains("Exported 3 DynamoDB items"), exported);
                byte[] objectFixture = Files.readAllBytes(project.resolve("build/fixtures/scenario/objects.json"));
                byte[] itemFixture = Files.readAllBytes(project.resolve("build/fixtures/scenario/items.json"));
                assertFalse(exported.contains("captured-value"), exported);
                String reset = runner(project, "resetAwsEmulator", "-PawsEmulator.scenario=empty",
                    "-PawsEmulator.resetScope=RESOURCES", "--parallel").build().getOutput();
                if (run > 0) assertTrue(reset.contains("Reusing configuration cache"), reset);
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb(); var sqs = clients.sqs()) {
                    assertTrue(s3.listObjectsV2(r -> r.bucket("scenario-assets")).contents().isEmpty());
                    assertEquals(0, dynamo.scan(r -> r.tableName("scenario-products")).count());
                    assertEquals("unrelated-payload", s3.getObjectAsBytes(r -> r.bucket("unrelated-assets").key("keep")).asUtf8String());
                    assertFalse(sqs.getQueueUrl(r -> r.queueName("unrelated-queue")).queueUrl().isEmpty());
                }
                String replay = runner(project, "resetAwsEmulatorReplay", "-PawsEmulator.resetScope=RESOURCES").build().getOutput();
                if (run > 0) assertTrue(replay.contains("Reusing configuration cache"), replay);
                assertFalse(replay.contains("Exported"), replay);
                assertEquals(container, containers());
                assertEquals(endpoint, connection().endpoint().toString());
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
                    assertEquals(3, s3.listObjectsV2(r -> r.bucket("scenario-assets")).contents().size());
                    var captured = s3.getObjectAsBytes(r -> r.bucket("scenario-assets").key("captured.bin"));
                    assertArrayEquals(new byte[] {0, 1, -1}, captured.asByteArray());
                    assertEquals("application/custom", captured.response().contentType());
                    assertEquals(Map.of("fixture", "captured"), captured.response().metadata());
                    assertEquals(3, dynamo.scan(r -> r.tableName("scenario-products")).count());
                    assertEquals("captured-value", dynamo.getItem(r -> r.tableName("scenario-products")
                        .key(Map.of("id", text("captured")))).item().get("body").s());
                }
                assertArrayEquals(objectFixture, Files.readAllBytes(project.resolve("build/fixtures/scenario/objects.json")));
                assertArrayEquals(itemFixture, Files.readAllBytes(project.resolve("build/fixtures/scenario/items.json")));
            }
            // The same definitions still provide an independent managed graph in a combined invocation.
            runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=RESOURCES", "verifyFixtures", "--parallel").build();
            assertDemo();
            assertEquals(container, containers());
            for (int run = 0; run < 2; run++) {
                String clean = runner(project, "cleanAwsEmulator").build().getOutput();
                if (run > 0) assertTrue(clean.contains("Reusing configuration cache"), clean);
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
                    assertEquals(List.of("unrelated-assets"), s3.listBuckets().buckets().stream().map(bucket -> bucket.name()).toList());
                    assertFalse(dynamo.listTables().tableNames().contains("scenario-products"));
                }
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    @Test void developmentScenariosReseedResetSwitchDownloadAndPurgeWithConfigurationCache() throws Exception {
        fixture();
        try {
            runner(project, "seedAwsEmulator").build();
            assertDemo();
            for (int run = 0; run < 2; run++) {
                var clients = new AwsClientFactory(connection(), 30);
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
                    s3.putObject(r -> r.bucket("scenario-assets").key("unrelated"), RequestBody.fromString("keep on seed"));
                    dynamo.putItem(r -> r.tableName("scenario-products").item(Map.of("id", text("unrelated"))));
                    dynamo.putItem(r -> r.tableName("scenario-products").item(Map.of("id", text("book"), "modified", text("replace on seed"))));
                }
                String seed = runner(project, "seedAwsEmulator").build().getOutput();
                assertTrue(seed.contains("Reusing configuration cache"), seed);
                try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
                    assertEquals(3, s3.listObjectsV2(r -> r.bucket("scenario-assets")).contents().size());
                    assertEquals(3, dynamo.scan(r -> r.tableName("scenario-products").consistentRead(true)).count());
                    var book = dynamo.getItem(r -> r.tableName("scenario-products").key(Map.of("id", text("book"))).consistentRead(true)).item();
                    assertFalse(book.containsKey("modified"));
                    assertTrue(book.containsKey("price"));
                }
                String reset = runner(project, "resetAwsEmulator", "--parallel", "--max-workers=4").build().getOutput();
                if (run == 1) assertTrue(reset.contains("Reusing configuration cache"), reset);
                assertDemo();
            }
            runner(project, "verifyScenario").build();
            String objects = runner(project, "listS3Objects", "--bucketName=scenario-assets", "--prefix=assets/", "downloadAsset").build().getOutput();
            assertTrue(objects.contains("assets/nested/message.txt"), objects);
            assertFalse(objects.contains("settings/app.json"), objects);
            assertEquals(Files.readString(project.resolve("fixtures/assets/nested/message.txt")),
                Files.readString(project.resolve("build/downloads/message.txt")));
            runner(project, "purgeDynamoDbTable", "--tableName=scenario-products").build();
            try (var dynamo = new AwsClientFactory(connection(), 30).dynamoDb()) {
                assertEquals(0, dynamo.scan(r -> r.tableName("scenario-products").consistentRead(true)).count());
                assertEquals(TableStatus.ACTIVE, dynamo.describeTable(r -> r.tableName("scenario-products")).table().tableStatus());
            }
            runner(project, "resetAwsEmulator", "-PawsEmulator.scenario=empty").build();
            runner(project, "verifyScenario", "-PawsEmulator.scenario=empty").build();
            runner(project, "resetAwsEmulatorFailedOrders").build();
            runner(project, "verifyScenario", "-PawsEmulator.scenario=failed-orders").build();
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    @Test void dataTasksAlsoWorkInManagedBuildsAndRunAgainOnCacheReuse() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "verifyFixtures").build().getOutput();
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertTrue(output.contains("Loaded 2 DynamoDB items"), output);
            assertTrue(output.contains("Uploaded 1 S3 objects"), output);
            assertNoContainers();
        }
    }

    @Test void sameDefinitionsCanSeedDevelopmentAndManagedTestsInOneInvocation() throws Exception {
        fixture();
        try {
            for (int run = 0; run < 2; run++) {
                String output = runner(project, "resetAwsEmulator", "verifyFixtures", "--parallel").build().getOutput();
                if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
                assertDemo();
                try (var homes = Files.list(project.resolve(".aws-emulator"))) {
                    assertFalse(homes.anyMatch(path -> path.getFileName().toString().startsWith("managed-")));
                }
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    private void assertDemo() throws Exception {
        var clients = new AwsClientFactory(connection(), 30);
        try (var s3 = clients.s3(); var dynamo = clients.dynamoDb()) {
            assertEquals(List.of("assets/nested/message.txt", "settings/app.json"), s3.listObjectsV2(r -> r.bucket("scenario-assets"))
                .contents().stream().map(object -> object.key()).sorted().toList());
            assertEquals("application/json", s3.headObject(r -> r.bucket("scenario-assets").key("settings/app.json")).contentType());
            var asset = s3.headObject(r -> r.bucket("scenario-assets").key("assets/nested/message.txt"));
            assertEquals("text/plain", asset.contentType());
            assertEquals(Map.of("fixture", "scenario-example"), asset.metadata());
            assertEquals(2, dynamo.scan(r -> r.tableName("scenario-products").consistentRead(true)).count());
            var book = dynamo.getItem(r -> r.tableName("scenario-products").key(Map.of("id", text("book"))).consistentRead(true)).item();
            assertArrayEquals(new byte[] {0, 1, -1}, book.get("attachment").b().asByteArray());
            assertEquals(2, book.get("details").m().get("ratings").l().size());
        }
    }

    private AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }

    private EmulatorConnection connection() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path path = paths.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(path.getFileName().toString()), "us-east-1", path);
        }
    }

    private void assertNoContainers() throws Exception {
        assertEquals("", containers());
    }

    private String containers() throws Exception {
        String containers = new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking scenario cleanup");
        return containers;
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("scenarios.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle") || relative.startsWith(".aws-emulator")
                        || relative.toString().equals("settings.gradle.kts")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"data-scenarios-integration\"\n");
    }
}
