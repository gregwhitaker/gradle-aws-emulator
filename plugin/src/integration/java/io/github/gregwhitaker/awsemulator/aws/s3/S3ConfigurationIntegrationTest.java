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

package io.github.gregwhitaker.awsemulator.aws.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class S3ConfigurationIntegrationTest {
    @TempDir Path project;

    @Test
    void developmentConfigurationReplacesPoliciesAndNotificationsAndPreservesObjectsAndOtherBuckets() throws Exception {
        fixture();
        try {
            runner(project, "configureBucket").build();
            var clients = new AwsClientFactory(connection(), 30);
            try (var s3 = clients.s3(); var sqs = clients.sqs()) {
                assertEquals(BucketVersioningStatus.ENABLED, s3.getBucketVersioning(r -> r.bucket("configured-bucket")).status());
                var configuration = s3.getBucketNotificationConfiguration(r -> r.bucket("configured-bucket"));
                assertEquals("uploads", configuration.queueConfigurations().get(0).id());
                assertEquals("uploads/", configuration.queueConfigurations().get(0).filter().key().filterRules().get(0).value());
                var json = new ObjectMapper();
                assertEquals(json.readTree(Files.readString(project.resolve("policy.json"))),
                    json.readTree(s3.getBucketPolicy(r -> r.bucket("configured-bucket")).policy()));

                for (String body : List.of("first", "second")) {
                    s3.putObject(r -> r.bucket("configured-bucket").key("uploads/hello.txt"), RequestBody.fromString(body));
                }
                assertEquals(2, s3.listObjectVersions(r -> r.bucket("configured-bucket")).versions().size());
                String queue = sqs.getQueueUrl(r -> r.queueName("bucket-events")).queueUrl();
                boolean delivered = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (!delivered && System.nanoTime() < deadline) {
                    for (var message : sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(2).maxNumberOfMessages(10)).messages()) {
                        delivered |= message.body().contains("hello.txt") && message.body().contains("configured-bucket");
                        sqs.deleteMessage(r -> r.queueUrl(queue).receiptHandle(message.receiptHandle()));
                    }
                }
                assertTrue(delivered, "Expected an S3 object-created notification in the SQS destination");

                // Neither configuration-cache reuse nor unchanged inputs may skip live configuration.
                s3.putBucketVersioning(r -> r.bucket("configured-bucket").versioningConfiguration(c -> c.status(BucketVersioningStatus.SUSPENDED)));
                s3.deleteBucketPolicy(r -> r.bucket("configured-bucket"));
                s3.putBucketNotificationConfiguration(r -> r.bucket("configured-bucket").notificationConfiguration(c -> {}));
                Files.writeString(project.resolve("policy.json"), policy("Replacement"));
                var repeated = runner(project, "configureBucket").build();
                assertTrue(repeated.getOutput().contains("Reusing configuration cache"), repeated.getOutput());
                for (String task : List.of("configurePolicy", "configureVersioning", "configureNotifications")) assertEquals(SUCCESS, repeated.task(":" + task).getOutcome());
                assertEquals(BucketVersioningStatus.ENABLED, s3.getBucketVersioning(r -> r.bucket("configured-bucket")).status());
                assertTrue(s3.getBucketPolicy(r -> r.bucket("configured-bucket")).policy().contains("Replacement"));
                assertEquals(1, s3.getBucketNotificationConfiguration(r -> r.bucket("configured-bucket")).queueConfigurations().size());

                runner(project, "configureVersioning", "--status=SUSPENDED", "clearNotifications", "deleteS3BucketPolicy", "--bucketName=configured-bucket").build();
                runner(project, "deleteS3BucketPolicy", "--bucketName=configured-bucket").build();
                assertEquals(BucketVersioningStatus.SUSPENDED, s3.getBucketVersioning(r -> r.bucket("configured-bucket")).status());
                assertTrue(s3.getBucketNotificationConfiguration(r -> r.bucket("configured-bucket")).queueConfigurations().isEmpty());
                assertEquals(404, assertThrows(S3Exception.class, () -> s3.getBucketPolicy(r -> r.bucket("configured-bucket"))).statusCode());
                assertEquals(2, s3.listObjectVersions(r -> r.bucket("configured-bucket")).versions().size());
                assertNull(s3.getBucketVersioning(r -> r.bucket("unrelated-bucket")).status());
                assertFalse(sqs.getQueueUrl(r -> r.queueName("bucket-events")).queueUrl().isBlank());

                String disposable = sqs.getQueueUrl(r -> r.queueName("disposable.fifo")).queueUrl();
                sqs.sendMessage(r -> r.queueUrl(disposable).messageGroupId("test").messageDeduplicationId("test").messageBody("discarded"));
                for (int run = 0; run < 2; run++) {
                    var deleted = runner(project, "deleteSqsQueues", "--queueNames=disposable.fifo,absent,disposable.fifo").build();
                    if (run > 0) assertTrue(deleted.getOutput().contains("Reusing configuration cache"), deleted.getOutput());
                }
                assertThrows(QueueDoesNotExistException.class, () -> sqs.getQueueUrl(r -> r.queueName("disposable.fifo")));
                assertFalse(sqs.getQueueUrl(r -> r.queueName("disposable-dlq.fifo")).queueUrl().isBlank());
                assertFalse(sqs.getQueueUrl(r -> r.queueName("bucket-events")).queueUrl().isBlank());
            }
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test
    void managedConfigurationAndDeletionReuseTheCacheAndCleanUpAfterSuccessAndFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            var result = runner(project, "verifyManaged", "-Pmanaged=true").build();
            if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
            for (String task : List.of("configurePolicy", "configureVersioning", "configureNotifications", "deleteDisposable")) {
                assertEquals(SUCCESS, result.task(":" + task).getOutcome());
            }
            assertTrue(result.getOutput().contains("bucket-events\t"), result.getOutput());
            assertFalse(result.getOutput().contains("disposable.fifo\thttps"), result.getOutput());
            assertEquals("", containers());
        }
        String output = runner(project, "failAfterSetup", "-Pmanaged=true").buildAndFail().getOutput();
        assertTrue(output.contains("intentional consumer failure"), output);
        assertEquals("", containers());
    }

    private String policy(String sid) {
        return """
            {"Version":"2012-10-17","Statement":[{"Sid":"%s","Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},
            "Action":"s3:GetObject","Resource":"arn:aws:s3:::configured-bucket/*"}]}
            """.formatted(sid);
    }

    private EmulatorConnection connection() throws Exception {
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking bucket task cleanup");
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'bucket-configuration-integration'\n");
        Files.writeString(project.resolve("policy.json"), policy("Original"));
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.aws.sqs.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import software.amazon.awssdk.services.s3.model.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator.defaultMode.set(providers.gradleProperty('managed').getOrElse('false').toBoolean() ? EmulatorMode.MANAGED : EmulatorMode.DEVELOPMENT)
            def buckets = tasks.register('createBuckets', CreateS3BucketsTask) { buckets.set(['configured-bucket', 'unrelated-bucket']) }
            def queue = tasks.register('createEvents', CreateSqsQueueTask) {
                queueName.set('bucket-events')
                attributes.put('Policy', '''{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"Service":"s3.amazonaws.com"},"Action":"sqs:SendMessage",
                  "Resource":"arn:aws:sqs:us-east-1:000000000000:bucket-events",
                  "Condition":{"ArnEquals":{"aws:SourceArn":"arn:aws:s3:::configured-bucket"}}}]}''')
            }
            def disposable = tasks.register('createDisposable', CreateSqsQueueWithDlqTask) {
                queueName.set('disposable.fifo')
                queueAttributes.put('FifoQueue', 'true')
            }
            def policy = tasks.register('configurePolicy', PutS3BucketPolicyTask) {
                dependsOn(buckets)
                bucketName.set('configured-bucket')
                policyFile.set(layout.projectDirectory.file('policy.json'))
            }
            def versioning = tasks.register('configureVersioning', PutS3BucketVersioningTask) {
                dependsOn(buckets)
                bucketName.set('configured-bucket')
                status.set(BucketVersioningStatus.ENABLED)
            }
            def notifications = tasks.register('configureNotifications', PutS3BucketNotificationConfigurationTask) {
                dependsOn(buckets, queue)
                bucketName.set('configured-bucket')
                notificationConfiguration.set(NotificationConfiguration.builder().queueConfigurations(
                    QueueConfiguration.builder().id('uploads').queueArn('arn:aws:sqs:us-east-1:000000000000:bucket-events')
                        .events(Event.S3_OBJECT_CREATED).filter(NotificationConfigurationFilter.builder()
                            .key(S3KeyFilter.builder().filterRules(FilterRule.builder().name(FilterRuleName.PREFIX).value('uploads/').build()).build()).build())
                        .build()).build())
            }
            tasks.register('configureBucket') { dependsOn(policy, versioning, notifications, disposable) }
            tasks.register('clearNotifications', PutS3BucketNotificationConfigurationTask) {
                bucketName.set('configured-bucket')
                notificationConfiguration.set(NotificationConfiguration.builder().build())
            }
            def deleteDisposable = tasks.register('deleteDisposable', DeleteSqsQueuesTask) {
                dependsOn(disposable)
                queueNames.add('disposable.fifo')
            }
            tasks.register('verifyManaged', ListSqsQueuesTask) { dependsOn('configureBucket', deleteDisposable) }
            tasks.register('failAfterSetup') {
                dependsOn('verifyManaged')
                doLast { throw new GradleException('intentional consumer failure') }
            }
            """);
    }
}
