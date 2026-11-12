package io.github.gregwhitaker.awsemulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTaskFunctionalTest {
    @TempDir Path project;

    @Test
    void modulesConfigureAllTaskTypesAndRegisterDevelopmentCommandsLazily() throws Exception {
        fixture("""
            val buckets = tasks.register<CreateS3BucketsTask>("createBuckets") { buckets.add("fixture-bucket") }
            tasks.register<CreateSqsQueuesTask>("createQueues") { queueNames.add("events") }
            tasks.register<CreateSqsQueueWithDlqTask>("createDlq") { queueName.set("events.fifo"); queueAttributes.put("FifoQueue", "true") }
            tasks.register<ListS3BucketsTask>("customListBuckets")
            tasks.register<PurgeS3BucketsTask>("customPurgeBuckets")
            tasks.register<DeleteS3BucketsTask>("customDeleteBuckets")
            tasks.register<ListSqsQueuesTask>("customListQueues")
            tasks.register<PurgeSqsQueuesTask>("customPurgeQueues")
            tasks.register<PublishSqsTask>("customPublish")
            check(buckets.get().mode.get() == EmulatorMode.MANAGED)
            check(tasks.named<CreateSqsQueueWithDlqTask>("createDlq").get().deadletterQueueName.get() == "events-dlq.fifo")
            listOf("listS3Buckets", "purgeS3Buckets", "deleteS3Buckets", "listSqsQueues", "purgeSqsQueues", "publishSqs").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            check(!tasks.named<DeleteS3BucketsTask>("deleteS3Buckets").get().force.get())
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        for (String task : new String[] {"createBuckets", "createQueues", "createDlq", "customListBuckets", "customPurgeBuckets",
            "customDeleteBuckets", "customListQueues", "customPurgeQueues", "customPublish", "listS3Buckets", "purgeS3Buckets",
            "deleteS3Buckets", "listSqsQueues", "purgeSqsQueues", "publishSqs"}) {
            assertTrue(output.contains(task + " - "), output);
        }
        assertTrue(output.contains("AWS Emulator - S3 tasks"), output);
        assertTrue(output.contains("AWS Emulator - SQS tasks"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        String help = runner(project, "help", "--task", "publishSqs").build().getOutput();
        for (String option : new String[] {"--mode", "--message", "--messageGroupId", "--queueNames"}) assertTrue(help.contains(option), help);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void validatesBulkDlqAndMessageInputsBeforeStartingDocker() throws Exception {
        fixture("""
            tasks.register<CreateS3BucketsTask>("badBuckets") { buckets.set(listOf("valid-bucket", "InvalidBucket")) }
            tasks.register<CreateSqsQueuesTask>("badQueues") { queueNames.set(listOf("valid", "bad/name")) }
            tasks.register<CreateSqsQueueWithDlqTask>("badDlq") { queueName.set("events"); deadletterQueueName.set("events") }
            tasks.register<PublishSqsTask>("badFifo") { queueNames.add("events.fifo"); messages.from("messages.txt") }
            tasks.register<PublishSqsTask>("badMessages") { queueNames.add("events"); messages.from("corrupt.tgz") }
            """);
        Files.writeString(project.resolve("messages.txt"), "message");
        Files.writeString(project.resolve("corrupt.tgz"), "not an archive");
        String output = runner(project, "badBuckets", "badQueues", "badDlq", "badFifo", "badMessages", "--continue").buildAndFail().getOutput();
        for (String message : new String[] {"bucketName must", "queueName must", "cannot be its own deadletter", "messageGroupId is required", "Cannot read SQS messages"}) {
            assertTrue(output.contains(message), output);
        }
        String cli = runner(project, "purgeS3Buckets", "--buckets=valid-bucket,", "purgeSqsQueues", "--queueNames=valid,bad/name", "--continue").buildAndFail().getOutput();
        assertTrue(cli.contains("bucketName must"), cli);
        assertTrue(cli.contains("queueName must"), cli);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(String tasks) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"service-task-fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.aws.sqs.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            """ + tasks);
    }
}
