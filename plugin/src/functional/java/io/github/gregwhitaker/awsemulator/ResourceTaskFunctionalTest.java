package io.github.gregwhitaker.awsemulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ResourceTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @ValueSource(strings = {"groovy", "kotlin"})
    void typedTasksConfigureLazilyInBothDslsAndReuseTheConfigurationCache(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'resource-fixture'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            tasks.register<CreateS3BucketTask>("createBucket") {
                bucketName.set("fixture-bucket")
            }
            tasks.register<CreateSqsQueueTask>("createQueue") {
                queueName.set("fixture.fifo")
                attributes.put("FifoQueue", "true")
                mode.set(EmulatorMode.DEVELOPMENT)
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('createBucket', CreateS3BucketTask) {
                bucketName.set('fixture-bucket')
            }
            tasks.register('createQueue', CreateSqsQueueTask) {
                queueName.set('fixture.fifo')
                attributes.put('FifoQueue', 'true')
                mode.set(EmulatorMode.DEVELOPMENT)
            }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - S3 tasks"), output);
        assertTrue(output.contains("createBucket - Creates or reuses an S3 bucket in AWS Emulator."), output);
        assertTrue(output.contains("AWS Emulator - SQS tasks"), output);
        assertTrue(output.contains("createQueue - Creates or reuses an SQS queue in AWS Emulator."), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void rejectsInvalidResourceInputsBeforeStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'resource-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('badBucket', CreateS3BucketTask) { bucketName.set('InvalidBucket') }
            tasks.register('badFifo', CreateSqsQueueTask) { queueName.set('events.fifo') }
            tasks.register('badTimeout', CreateSqsQueueTask) {
                queueName.set('events')
                apiCallTimeoutSeconds.set(0)
            }
            """);
        assertTrue(runner(project, "badBucket").buildAndFail().getOutput().contains("bucketName must"));
        assertTrue(runner(project, "badFifo").buildAndFail().getOutput().contains("FIFO queues require"));
        assertTrue(runner(project, "badTimeout").buildAndFail().getOutput().contains("apiCallTimeoutSeconds must be positive"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
