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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class S3ConfigurationTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(strings = {"kotlin", "groovy"})
    void configuresBothDslsAndCachesSdkNotificationModelsWithoutDocker(String language) throws Exception {
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'bucket-configuration'\n");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import software.amazon.awssdk.services.s3.model.*
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("no-docker-during-configuration") }
            tasks.register<PutS3BucketPolicyTask>("policy") { bucketName.set("fixture-bucket"); policy.set("{}") }
            tasks.register<PutS3BucketVersioningTask>("versioning") { bucketName.set("fixture-bucket"); status.set(BucketVersioningStatus.ENABLED) }
            tasks.register<PutS3BucketNotificationConfigurationTask>("notifications") {
                bucketName.set("fixture-bucket")
                notificationConfiguration.set(NotificationConfiguration.builder().queueConfigurations(
                    QueueConfiguration.builder().queueArn("arn:aws:sqs:us-east-1:000000000000:events")
                        .events(Event.S3_OBJECT_CREATED).build()).build())
            }
            check(tasks.named<PutS3BucketPolicyTask>("policy").get().mode.get() == EmulatorMode.MANAGED)
            check(tasks.named<DeleteS3BucketPolicyTask>("deleteS3BucketPolicy").get().mode.get() == EmulatorMode.DEVELOPMENT)
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import software.amazon.awssdk.services.s3.model.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('no-docker-during-configuration') }
            tasks.register('policy', PutS3BucketPolicyTask) { bucketName.set('fixture-bucket'); policy.set('{}') }
            tasks.register('versioning', PutS3BucketVersioningTask) { bucketName.set('fixture-bucket'); status.set(BucketVersioningStatus.ENABLED) }
            tasks.register('notifications', PutS3BucketNotificationConfigurationTask) {
                bucketName.set('fixture-bucket')
                notificationConfiguration.set(NotificationConfiguration.builder().queueConfigurations(
                    QueueConfiguration.builder().queueArn('arn:aws:sqs:us-east-1:000000000000:events')
                        .events(Event.S3_OBJECT_CREATED).build()).build())
            }
            assert tasks.named('policy').get().mode.get() == EmulatorMode.MANAGED
            assert tasks.named('deleteS3BucketPolicy').get().mode.get() == EmulatorMode.DEVELOPMENT
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "policy", "versioning", "notifications", "--dry-run").build().getOutput();
            for (String name : new String[] {"policy", "versioning", "notifications"}) assertTrue(output.contains(":" + name + " SKIPPED"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        String help = runner(project, "help", "--task", "policy").build().getOutput();
        for (String option : new String[] {"--bucketName", "--policyFile", "--runtime", "--mode"}) assertTrue(help.contains(option), help);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void rejectsInvalidConfigurationBeforeStartingAnEmulator() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'invalid-config'\n");
        Files.writeString(project.resolve("policy.json"), "{}");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import software.amazon.awssdk.services.s3.model.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('no-docker-for-invalid-input') }
            tasks.register('missingPolicy', PutS3BucketPolicyTask) { bucketName.set('fixture-bucket') }
            tasks.register('bothPolicies', PutS3BucketPolicyTask) { bucketName.set('fixture-bucket'); policy.set('{}'); policyFile.set(file('policy.json')) }
            tasks.register('badPolicy', PutS3BucketPolicyTask) { bucketName.set('fixture-bucket'); policy.set('private-value-not-json') }
            tasks.register('badVersioning', PutS3BucketVersioningTask) { bucketName.set('fixture-bucket'); status.set(BucketVersioningStatus.UNKNOWN_TO_SDK_VERSION) }
            tasks.register('badNotification', PutS3BucketNotificationConfigurationTask) {
                bucketName.set('fixture-bucket')
                notificationConfiguration.set(NotificationConfiguration.builder().queueConfigurations(QueueConfiguration.builder().build()).build())
            }
            tasks.register('emptyQueues', io.github.gregwhitaker.awsemulator.aws.sqs.DeleteSqsQueuesTask) { queueNames.set([]) }
            """);
        String output = runner(project, "missingPolicy", "bothPolicies", "badPolicy", "badVersioning", "badNotification", "emptyQueues", "--continue").buildAndFail().getOutput();
        for (String message : new String[] {"Set exactly one", "must be a JSON object", "ENABLED or SUSPENDED", "destination must be", "at least one queue"}) {
            assertTrue(output.contains(message), output);
        }
        assertFalse(output.contains("private-value-not-json"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
