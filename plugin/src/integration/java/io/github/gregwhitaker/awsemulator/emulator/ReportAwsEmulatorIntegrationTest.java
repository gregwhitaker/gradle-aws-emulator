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
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.UnexpectedBuildFailure;
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
            runner(project, "seed", "-Pdevelopment=true").build();
            var clients = new AwsClientFactory(developmentConnection(), 30);
            try (var s3 = clients.s3(); var sqs = clients.sqs(); var sns = clients.sns(); var kms = clients.kms();
                    var ssm = clients.ssm(); var secrets = clients.secretsManager(); var logs = clients.cloudWatchLogs();
                    var cognito = clients.cognitoIdentityProvider(); var ses = clients.sesV2()) {
                s3.putBucketVersioning(r -> r.bucket("report-files").versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
                String queue = sqs.getQueueUrl(r -> r.queueName("report-events")).queueUrl();
                String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(queue).attributeNamesWithStrings("QueueArn")).attributesAsStrings().get("QueueArn");
                String topicArn = sns.listTopics().topics().get(0).topicArn();
                sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(queueArn));
                sqs.sendMessage(r -> r.queueUrl(queue).messageBody("private-message-body"));
                logs.putLogEvents(r -> r.logGroupName("/report/logs").logStreamName("application")
                    .logEvents(e -> e.timestamp(System.currentTimeMillis()).message("private-log-body")));
                String pool = cognito.listUserPools(r -> r.maxResults(60)).userPools().get(0).id();
                String clientSecret = cognito.createUserPoolClient(r -> r.userPoolId(pool).clientName("confidential").generateSecret(true))
                    .userPoolClient().clientSecret();
                assertNotNull(clientSecret);
                int keyCount = kms.listKeys().keys().size();
                for (int run = 0; run < 2; run++) {
                    var result = buildWithReport("development", "reportAwsEmulator", "reportAwsEmulator", "--runtime", "integration");
                    assertTrue(result.getOutput().contains("AWS Emulator report is incomplete"), result.getOutput());
                    if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                    var report = report("development", "reportAwsEmulator");
                    assertInventory(report);
                    assertEquals("DEVELOPMENT", report.path("runtime").path("mode").asText());
                    assertEquals("Enabled", resource(report, "S3").path("configuration").path("versioning").asText());
                    assertEquals(queueArn, resource(report, "SNS").path("configuration").path("subscriptions").get(0).path("endpoint").asText());
                    assertEquals(run + 1, service(report, "SQS").path("resources").size());
                    assertFalse(report.toString().contains("private-message-body"));
                    assertFalse(report.toString().contains("private-log-body"));
                    assertFalse(report.toString().contains(clientSecret));
                    String html = Files.readString(project.resolve("build/reports/aws-emulator/integration/development/reportAwsEmulator/index.html"));
                    assertFalse(html.contains(clientSecret));
                    assertFalse(html.contains("private-log-body"));
                    if (run == 0) sqs.createQueue(r -> r.queueName("created-between-reports"));
                }
                String strict = runner(project, "reportAwsEmulator", "--runtime", "integration", "--fail-on-error").buildAndFail().getOutput();
                assertTrue(strict.contains("AWS Emulator report is incomplete"), strict);
                assertInventory(report("development", "reportAwsEmulator"));
                assertEquals("private-message-body", sqs.receiveMessage(r -> r.queueUrl(queue)).messages().get(0).body());
                assertEquals(1, s3.listBuckets().buckets().size());
                assertEquals(keyCount, kms.listKeys().keys().size(), "Reporting must not materialize keys for unbound AWS aliases");
                assertEquals("private-ssm-value", ssm.getParameter(r -> r.name("/report/password").withDecryption(true)).parameter().value());
                assertEquals("private-secret-value", secrets.getSecretValue(r -> r.secretId("report-secret")).secretString());
                assertEquals("private-log-body", logs.getLogEvents(r -> r.logGroupName("/report/logs").logStreamName("application").startFromHead(true)).events().get(0).message());
                assertEquals("private-template-body", ses.getEmailTemplate(r -> r.templateName("report-template")).templateContent().text());
            }
        } finally { runner(project, "stopAwsEmulatorIntegration").build(); }
        assertNoContainers();
    }

    @Test void managedFinalizerCapturesBeforeCleanupOnSuccessCacheReuseAndTaskFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = buildWithReport("managed", "reportAfterTests", "verify").getOutput();
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
            String output = buildWithReport("development", "reportAwsEmulatorIntegration", "reportAwsEmulatorIntegration").getOutput();
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
        assertEquals(13, report.path("services").size());
        for (var section : report.path("services")) {
            // The pinned emulator implements template create/get, but SES v2 template listing is still a stub.
            if (section.path("service").asText().equals("SES")) {
                assertEquals("PARTIAL", section.path("status").asText(), section.toPrettyString());
                assertEquals(1, section.path("issues").size(), section.toPrettyString());
                assertEquals("ListEmailTemplates", section.path("issues").get(0).path("operation").asText());
                assertEquals("UNSUPPORTED", section.path("issues").get(0).path("kind").asText());
            } else assertEquals("COMPLETE", section.path("status").asText(), section.toPrettyString());
            assertFalse(section.path("resources").isEmpty(), section.toPrettyString());
        }
        assertEquals("report-files", resource(report, "S3").path("id").asText());
        assertEquals("report-items", resource(report, "DynamoDB").path("id").asText());
        assertEquals("id", resource(report, "DynamoDB").path("configuration").path("keySchema").get(0).path("name").asText());
        assertEquals(4, resource(report, "CloudFormation").path("configuration").path("resources").size());
        assertFalse(report.toString().contains("private-parameter"));
        assertFalse(report.toString().contains("private-output"));
        assertEquals("SecureString", resource(report, "SSM").path("configuration").path("type").asText());
        assertEquals("report-secret", resource(report, "Secrets Manager").path("configuration").path("name").asText());
        assertEquals(1, resource(report, "Kinesis").path("configuration").path("openShardCount").asInt());
        var bus = resource(report, "EventBridge", "report-bus").path("configuration");
        assertEquals("orders", bus.path("rules").get(0).path("name").asText());
        assertEquals("queue", bus.path("rules").get(0).path("targets").get(0).path("id").asText());
        assertFalse(resource(report, "KMS", "alias/report-key").path("configuration").path("targetKeyId").asText().isBlank());
        assertEquals("application", resource(report, "CloudWatch Logs").path("configuration").path("streams").get(0).path("name").asText());
        var pool = resource(report, "Cognito").path("configuration");
        assertEquals("report-users", pool.path("name").asText());
        assertFalse(pool.path("clients").isEmpty());
        assertEquals("report-admins", pool.path("groups").get(0).path("name").asText());
        assertEquals("AWS::SES::EmailIdentity", resource(report, "SES", "sender@example.test").path("type").asText());
        assertEquals("sns-events", resource(report, "SES", "report-mail").path("configuration").path("eventDestinations").get(0).path("name").asText());
        for (String omitted : List.of("private-ssm-value", "private-secret-value", "private-template-subject", "private-template-body",
                "private-pattern", "private-user", "Private-Password123!")) assertFalse(report.toString().contains(omitted), omitted);
    }

    private JsonNode report(String mode, String task) throws Exception {
        Path directory = project.resolve("build/reports/aws-emulator/integration/" + mode + "/" + task);
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("Emulator configuration"));
        for (String omitted : List.of("private-ssm-value", "private-secret-value", "private-template-subject", "private-template-body",
                "private-pattern", "private-user", "Private-Password123!")) assertFalse(html.contains(omitted), omitted);
        return new ObjectMapper().readTree(directory.resolve("report.json").toFile());
    }

    private BuildResult buildWithReport(String mode, String task, String... arguments) throws Exception {
        try { return runner(project, arguments).build(); }
        catch (UnexpectedBuildFailure failure) {
            Path json = project.resolve("build/reports/aws-emulator/integration/" + mode + "/" + task + "/report.json");
            if (Files.isRegularFile(json)) {
                var snapshot = new ObjectMapper().readTree(json.toFile());
                throw new AssertionError("Report collection issues: " + snapshot.findValues("issues"), failure);
            }
            throw failure;
        }
    }

    private JsonNode resource(JsonNode report, String service) { return service(report, service).path("resources").get(0); }
    private JsonNode resource(JsonNode report, String service, String id) {
        for (var resource : service(report, service).path("resources")) if (resource.path("id").asText().equals(id)) return resource;
        throw new AssertionError("Missing " + service + " resource " + id);
    }
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
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.aws.ssm.PutSsmParameterTask
            import io.github.gregwhitaker.awsemulator.aws.secretsmanager.PutSecretTask
            import io.github.gregwhitaker.awsemulator.aws.eventbridge.*
            import io.github.gregwhitaker.awsemulator.aws.kinesis.CreateKinesisStreamTask
            import io.github.gregwhitaker.awsemulator.aws.kms.CreateKmsKeyTask
            import io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.*
            import io.github.gregwhitaker.awsemulator.aws.cognito.*
            import io.github.gregwhitaker.awsemulator.aws.ses.*
            import io.github.gregwhitaker.awsemulator.emulator.ReportAwsEmulatorTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { runtimes { register('integration') } }
            def setupMode = providers.gradleProperty('development').getOrElse('false').toBoolean() ? EmulatorMode.DEVELOPMENT : EmulatorMode.MANAGED
            tasks.withType(AbstractAwsResourceTask).configureEach {
                if (!(it instanceof ReportAwsEmulatorTask)) {
                    runtimeName.set('integration')
                    mode.set(setupMode)
                }
            }
            def stack = tasks.register('seedStack', DeployCloudFormationStackTask) {
                runtimeName.set('integration')
                stackName.set('report-stack')
                templateFile.set(layout.projectDirectory.file('stack.yaml'))
            }
            def key = tasks.register('key', CreateKmsKeyTask) { aliasName.set('alias/report-key') }
            def parameter = tasks.register('parameter', PutSsmParameterTask) {
                dependsOn(key); parameterName.set('/report/password'); value.set('private-ssm-value')
                type.set('SecureString'); keyId.set('alias/report-key')
            }
            def secret = tasks.register('secret', PutSecretTask) {
                dependsOn(key); secretName.set('report-secret'); value.set('private-secret-value'); keyId.set('alias/report-key')
            }
            def bus = tasks.register('bus', CreateEventBusTask) { eventBusName.set('report-bus') }
            def rule = tasks.register('rule', PutEventBridgeRuleTask) {
                dependsOn(bus); eventBusName.set('report-bus'); ruleName.set('orders'); eventPattern.set('{"source":["private-pattern"]}')
            }
            def target = tasks.register('target', PutEventBridgeSqsTargetTask) {
                dependsOn(rule, stack); eventBusName.set('report-bus'); ruleName.set('orders'); targetId.set('queue'); queueName.set('report-events')
            }
            def stream = tasks.register('stream', CreateKinesisStreamTask) { streamName.set('report-records') }
            def group = tasks.register('logGroup', CreateLogGroupTask) { logGroupName.set('/report/logs') }
            def logs = tasks.register('logStream', CreateLogStreamTask) {
                dependsOn(group); logGroupName.set('/report/logs'); logStreamName.set('application')
            }
            def pool = tasks.register('pool', CreateCognitoUserPoolTask) { userPoolName.set('report-users') }
            def client = tasks.register('client', CreateCognitoUserPoolClientTask) {
                dependsOn(pool); userPoolName.set('report-users'); clientName.set('report-app')
            }
            def users = tasks.register('users', SeedCognitoUsersTask) {
                dependsOn(pool); userPoolName.set('report-users'); fixtures.set(layout.projectDirectory.file('users.json'))
            }
            def identity = tasks.register('identity', CreateSesEmailIdentityTask) { emailIdentity.set('sender@example.test') }
            def template = tasks.register('template', PutSesEmailTemplateTask) {
                templateName.set('report-template'); subject.set('private-template-subject'); textBody.set('private-template-body')
            }
            def configuration = tasks.register('configuration', CreateSesConfigurationSetTask) { configurationSetName.set('report-mail') }
            def destination = tasks.register('destination', PutSesSnsEventDestinationTask) {
                dependsOn(configuration, stack); configurationSetName.set('report-mail'); eventDestinationName.set('sns-events')
                topicArn.set('arn:aws:sns:us-east-1:000000000000:report-notifications')
            }
            def seed = tasks.register('seed') { dependsOn(stack, parameter, secret, target, stream, logs, client, users, identity, template, destination) }
            def report = tasks.register('reportAfterTests', ReportAwsEmulatorTask) {
                runtimeName.set('integration')
                mode.set(EmulatorMode.MANAGED)
            }
            def shouldFail = providers.gradleProperty('failConsumer').isPresent()
            tasks.register('verify') {
                dependsOn(seed)
                finalizedBy(report)
                doLast { if (shouldFail) throw new GradleException('deliberate consumer failure') }
            }
            """);
        Files.writeString(project.resolve("users.json"), """
            [{"username":"private-user","password":"Private-Password123!","groups":["report-admins"]}]
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
