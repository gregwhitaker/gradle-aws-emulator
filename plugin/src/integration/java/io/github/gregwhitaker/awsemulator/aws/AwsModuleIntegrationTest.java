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
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class AwsModuleIntegrationTest {
    @TempDir Path project;

    @Test void managedExampleRoundTripsAllSixServicesOnCacheReuseAndCleansUpOnFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "verifyServices").build().getOutput();
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertTrue(Files.readString(project.resolve("build/logs/filtered.json")).contains("application ready"));
            assertTrue(Files.readString(project.resolve("build/logs/events.json")).contains("application ready"));
            assertNoContainers();
        }
        String failure = runner(project, "verifyServices", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(failure.contains("There were failing tests"), failure);
        assertNoContainers();
    }

    @Test void developmentProvisioningIsRepeatableAndConflictsRequireOverwrite() throws Exception {
        fixture();
        try {
            runner(project, "provisionServices", "-Pdevelopment=true").build();
            var clients = new AwsClientFactory(connection(), 30);
            try (var ssm = clients.ssm(); var secrets = clients.secretsManager(); var events = clients.eventBridge(); var kms = clients.kms()) {
                long parameterVersion = ssm.getParameter(r -> r.name("/example/service/token").withDecryption(true)).parameter().version();
                String secretVersion = secrets.getSecretValue(r -> r.secretId("example/database")).versionId();
                String keyId = kms.describeKey(r -> r.keyId("alias/example-services")).keyMetadata().keyId();
                runner(project, "provisionServices", "-Pdevelopment=true").build();
                assertEquals(parameterVersion, ssm.getParameter(r -> r.name("/example/service/token").withDecryption(true)).parameter().version());
                assertEquals(secretVersion, secrets.getSecretValue(r -> r.secretId("example/database")).versionId());
                assertEquals(keyId, kms.describeKey(r -> r.keyId("alias/example-services")).keyMetadata().keyId());
                assertEquals(1, events.listTargetsByRule(r -> r.eventBusName("example-services").rule("orders")).targets().size());
                Path fixture = project.resolve("fixtures/secrets.json");
                Files.writeString(fixture, Files.readString(fixture).replace("local-password", "replacement-password"));
                String conflict = runner(project, "seedSecrets", "-Pdevelopment=true").buildAndFail().getOutput();
                assertTrue(conflict.contains("overwrite=true"), conflict);
                assertFalse(conflict.contains("replacement-password"), conflict);
                runner(project, "seedSecrets", "-Pdevelopment=true", "-Poverwrite=true").build();
                assertTrue(secrets.getSecretValue(r -> r.secretId("example/database")).secretString().contains("replacement-password"));
            }
            runner(project, "listSsmParameters", "listSecrets", "listEventBuses", "listEventBridgeRules", "--eventBusName=example-services",
                "listKinesisStreams", "listKmsKeys", "listLogGroups").build();
            for (int run = 0; run < 2; run++) {
                runner(project, "deleteEventBridgeRule", "--eventBusName=example-services", "--ruleName=orders",
                    "deleteEventBus", "--eventBusName=example-services", "deleteKinesisStream", "--streamName=example-service-records",
                    "deleteLogStream", "--logGroupName=/example/services", "--logStreamName=application",
                    "deleteLogGroup", "--logGroupName=/example/services",
                    "deleteSsmParameters", "--names=/example/service/url", "deleteSecrets", "--names=example/database", "--force",
                    "scheduleKmsKeyDeletion", "--keyId=alias/example-services").build();
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("services.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle") || relative.startsWith(".aws-emulator")
                    || relative.toString().equals("settings.gradle.kts")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"service-expansion-integration\"\n");
    }
    private EmulatorConnection connection() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path path = paths.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(path.getFileName().toString()), "us-east-1", path);
        }
    }
    private void assertNoContainers() throws Exception {
        String result = new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()), "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking cleanup");
        assertEquals("", result);
    }
}
