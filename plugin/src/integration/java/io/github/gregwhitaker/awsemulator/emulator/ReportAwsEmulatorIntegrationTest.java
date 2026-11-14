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

package io.github.gregwhitaker.awsemulator.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.testsupport.ExternalEmulatorFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ReportAwsEmulatorIntegrationTest {
    @TempDir Path project;

    @Test void developmentReportInspectsAllServicesAndRelationshipsWithoutChangingResources() throws Exception {
        fixture();
        try {
            String absent = runner(project, "reportAwsEmulator", "--runtime", "integration").buildAndFail().getOutput();
            assertTrue(absent.contains("development instance is not running"), absent);
            assertNoContainers();
            runner(project, "seed", "--mode", "DEVELOPMENT").build();
            var clients = new AwsClientFactory(developmentConnection(), 30);
            try (var s3 = clients.s3(); var sqs = clients.sqs(); var sns = clients.sns()) {
                s3.putBucketVersioning(r -> r.bucket("report-files").versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
                String queue = sqs.getQueueUrl(r -> r.queueName("report-events")).queueUrl();
                String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(queue).attributeNamesWithStrings("QueueArn")).attributesAsStrings().get("QueueArn");
                String topicArn = sns.listTopics().topics().get(0).topicArn();
                sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(queueArn));
                sqs.sendMessage(r -> r.queueUrl(queue).messageBody("private-message-body"));
                for (int run = 0; run < 2; run++) {
                    var result = runner(project, "reportAwsEmulator", "--runtime", "integration", "--fail-on-error").build();
                    if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                    var report = report("development", "reportAwsEmulator");
                    assertInventory(report);
                    assertEquals("DEVELOPMENT", report.path("runtime").path("mode").asText());
                    assertEquals("Enabled", resource(report, "S3").path("configuration").path("versioning").asText());
                    assertEquals(queueArn, resource(report, "SNS").path("configuration").path("subscriptions").get(0).path("endpoint").asText());
                    assertEquals(run + 1, service(report, "SQS").path("resources").size());
                    assertFalse(report.toString().contains("private-message-body"));
                    if (run == 0) sqs.createQueue(r -> r.queueName("created-between-reports"));
                }
                assertEquals("private-message-body", sqs.receiveMessage(r -> r.queueUrl(queue)).messages().get(0).body());
                assertEquals(1, s3.listBuckets().buckets().size());
            }
        } finally { runner(project, "stopAwsEmulatorIntegration").build(); }
        assertNoContainers();
    }

    @Test void managedFinalizerCapturesBeforeCleanupOnSuccessCacheReuseAndTaskFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "verify").build().getOutput();
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            assertInventory(report("managed", "reportAfterTests"));
            assertNoContainers();
        }
        String failed = runner(project, "verify", "-PfailConsumer=true").buildAndFail().getOutput();
        assertTrue(failed.contains("deliberate consumer failure"), failed);
        assertInventory(report("managed", "reportAfterTests"));
        assertNoContainers();
    }

    @Test void externalReportUsesSuppliedConnectionAndLeavesItsResourcesRunning() throws Exception {
        fixture();
        try (var emulator = new ExternalEmulatorFixture(project.resolve("external-runtime"))) {
            var connection = emulator.connection();
            Files.writeString(project.resolve("build.gradle"), """
                awsEmulator {
                    dockerExecutable.set('must-not-invoke-docker')
                    runtimes.named('integration') {
                        external {
                            endpoint.set('%s')
                            credentialsFile.set(file('%s'))
                            certificateFile.set(file('%s'))
                        }
                    }
                }
                """.formatted(connection.endpoint(), connection.credentials(), connection.certificate()), StandardOpenOption.APPEND);
            runner(project, "seed").build();
            String output = runner(project, "reportAwsEmulatorIntegration", "--fail-on-error").build().getOutput();
            var report = report("development", "reportAwsEmulatorIntegration");
            assertFalse(service(report, "S3").path("resources").isEmpty(), output + "\n" + report.toPrettyString());
            assertInventory(report);
            assertTrue(report.path("runtime").path("external").asBoolean());
            try (var client = new AwsClientFactory(connection, 30).s3()) {
                assertEquals("report-files", client.listBuckets().buckets().get(0).name());
            }
        }
    }

    private void assertInventory(JsonNode report) throws Exception {
        assertEquals("integration", report.path("runtime").path("name").asText());
        assertEquals(5, report.path("services").size());
        for (var section : report.path("services")) {
            assertEquals("COMPLETE", section.path("status").asText(), section.toPrettyString());
            assertFalse(section.path("resources").isEmpty(), section.toPrettyString());
        }
        assertEquals("report-files", resource(report, "S3").path("id").asText());
        assertEquals("report-items", resource(report, "DynamoDB").path("id").asText());
        assertEquals("id", resource(report, "DynamoDB").path("configuration").path("keySchema").get(0).path("name").asText());
        assertEquals(4, resource(report, "CloudFormation").path("configuration").path("resources").size());
        assertFalse(report.toString().contains("private-parameter"));
        assertFalse(report.toString().contains("private-output"));
    }

    private JsonNode report(String mode, String task) throws Exception {
        Path directory = project.resolve("build/reports/aws-emulator/integration/" + mode + "/" + task);
        assertTrue(Files.readString(directory.resolve("index.html")).contains("Emulator configuration"));
        return new ObjectMapper().readTree(directory.resolve("report.json").toFile());
    }

    private JsonNode resource(JsonNode report, String service) { return service(report, service).path("resources").get(0); }
    private JsonNode service(JsonNode report, String name) {
        for (var section : report.path("services")) if (section.path("service").asText().equals(name)) return section;
        throw new AssertionError("Missing service " + name);
    }

    private EmulatorConnection developmentConnection() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator/runtimes/integration/development/.aws/emulator"))) {
            Path path = paths.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(path.getFileName().toString()), "us-east-1", path);
        }
    }

    private void assertNoContainers() throws Exception {
        assertEquals("", new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath() + "\nintegration"),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking report fixture cleanup"));
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'report-integration'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
            import io.github.gregwhitaker.awsemulator.emulator.ReportAwsEmulatorTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { runtimes { register('integration') } }
            def seed = tasks.register('seed', DeployCloudFormationStackTask) {
                runtimeName.set('integration')
                stackName.set('report-stack')
                templateFile.set(layout.projectDirectory.file('stack.yaml'))
            }
            def report = tasks.register('reportAfterTests', ReportAwsEmulatorTask) {
                runtimeName.set('integration')
                mode.set(EmulatorMode.MANAGED)
                failOnError.set(true)
            }
            def shouldFail = providers.gradleProperty('failConsumer').isPresent()
            tasks.register('verify') {
                dependsOn(seed)
                finalizedBy(report)
                doLast { if (shouldFail) throw new GradleException('deliberate consumer failure') }
            }
            """);
        Files.writeString(project.resolve("stack.yaml"), """
            Parameters:
              Password: {Type: String, NoEcho: true, Default: private-parameter}
            Resources:
              Files:
                Type: AWS::S3::Bucket
                Properties: {BucketName: report-files}
              Events:
                Type: AWS::SQS::Queue
                Properties: {QueueName: report-events, VisibilityTimeout: 45}
              Notifications:
                Type: AWS::SNS::Topic
                Properties: {TopicName: report-notifications}
              Items:
                Type: AWS::DynamoDB::Table
                Properties:
                  TableName: report-items
                  BillingMode: PAY_PER_REQUEST
                  AttributeDefinitions: [{AttributeName: id, AttributeType: S}]
                  KeySchema: [{AttributeName: id, KeyType: HASH}]
            Outputs:
              Token: {Value: private-output}
            """);
    }
}
