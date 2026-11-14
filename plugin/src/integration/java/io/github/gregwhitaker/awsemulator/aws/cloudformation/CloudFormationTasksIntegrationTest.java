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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationTasksIntegrationTest {
    @TempDir Path project;

    @Test void managedStacksProvisionUsableResourcesOnCacheReuseAndCleanUpAfterConsumerFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "integrationTest").build().getOutput();
            assertTrue(output.contains("CloudFormation-created S3, SQS, and DynamoDB resources verified"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            assertEquals("cloudformation-example-files", outputs().path("BucketName").asText());
            assertNoContainers();
        }
        String failure = runner(project, "integrationTest", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(failure.contains("There were failing tests"), failure);
        assertNoContainers();
    }

    @Test void developmentStackSupportsNoOpUpdateReplacementListingAndRepeatableDeletion() throws Exception {
        fixture();
        try {
            runner(project, "deployExampleStack", "-Pdevelopment=true").build();
            var factory = new AwsClientFactory(connection(), 30);
            try (var s3 = factory.s3(); var sqs = factory.sqs(); var dynamo = factory.dynamoDb();
                 var cloudFormation = factory.cloudFormation()) {
                String originalQueue = outputs().path("QueueUrl").asText();
                s3.headBucket(r -> r.bucket("cloudformation-example-files"));
                assertEquals("cloudformation-example-items",
                    dynamo.describeTable(r -> r.tableName("cloudformation-example-items")).table().tableName());
                String repeated = runner(project, "deployExampleStack", "-Pdevelopment=true").build().getOutput();
                assertTrue(repeated.contains("Reusing configuration cache"), repeated);
                assertEquals(originalQueue, outputs().path("QueueUrl").asText());
                assertEquals(1, cloudFormation.describeStacks().stacks().size());

                runner(project, "deployExampleStack", "-Pdevelopment=true", "-PqueueName=cloudformation-updated-events").build();
                String newQueue = outputs().path("QueueUrl").asText();
                assertNotEquals(originalQueue, newQueue);
                assertEquals(newQueue, sqs.getQueueUrl(r -> r.queueName("cloudformation-updated-events")).queueUrl());
                sqs.sendMessage(r -> r.queueUrl(newQueue).messageBody("updated"));
                assertEquals("updated", sqs.receiveMessage(r -> r.queueUrl(newQueue)).messages().get(0).body());
                assertThrows(QueueDoesNotExistException.class,
                    () -> sqs.getQueueUrl(r -> r.queueName("cloudformation-example-events")));
                String listed = runner(project, "listCloudFormationStacks").build().getOutput();
                assertTrue(listed.contains("cloudformation-example (UPDATE_COMPLETE)"), listed);
                for (int run = 0; run < 2; run++) {
                    runner(project, "deleteCloudFormationStack", "--stackName=cloudformation-example").build();
                }
                assertTrue(s3.listBuckets().buckets().isEmpty());
                assertTrue(sqs.listQueues().queueUrls().isEmpty());
                ServiceSupport.await("checking CloudFormation table deletion", 15, () -> {
                    try { dynamo.describeTable(r -> r.tableName("cloudformation-example-items")); return false; }
                    catch (ResourceNotFoundException absent) { return true; }
                });
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    @Test void provisioningFailureFailsTheTaskAndCleansUpItsManagedRuntime() throws Exception {
        fixture();
        Files.writeString(project.resolve("fixtures/stack.yaml"), """
            Parameters:
              QueueName: {Type: String}
            Resources:
              InvalidQueue:
                Type: AWS::SQS::Queue
                Properties:
                  QueueName: invalid.queue.name
            """);
        String output = runner(project, "deployExampleStack").buildAndFail().getOutput();
        assertTrue(output.matches("(?s).*CloudFormation stack cloudformation-example is (CREATE_FAILED|ROLLBACK_[A-Z_]+).*"), output);
        assertFalse(Files.exists(project.resolve("build/aws-emulator/cloudformation/deployExampleStack/outputs.json")));
        assertNoContainers();
    }

    private com.fasterxml.jackson.databind.JsonNode outputs() throws Exception {
        return new ObjectMapper().readTree(project.resolve("build/aws-emulator/cloudformation/deployExampleStack/outputs.json").toFile());
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("cloudformation.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle") || relative.startsWith(".aws-emulator")
                    || relative.toString().equals("settings.gradle.kts")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"cloudformation-integration\"\n");
    }

    private EmulatorConnection connection() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path path = paths.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(path.getFileName().toString()), "us-east-1", path);
        }
    }

    private void assertNoContainers() throws Exception {
        assertEquals("", new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking CloudFormation cleanup"));
    }
}
