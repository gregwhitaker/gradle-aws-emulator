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
        String containers = new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking scenario cleanup");
        assertEquals("", containers);
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
