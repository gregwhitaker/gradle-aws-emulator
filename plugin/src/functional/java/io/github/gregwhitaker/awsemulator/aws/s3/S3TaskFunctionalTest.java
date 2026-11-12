package io.github.gregwhitaker.awsemulator.aws.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class S3TaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @ValueSource(strings = {"groovy", "kotlin"})
    void typedTasksConfigureLazilyInBothDslsAndReuseTheConfigurationCache(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 's3-fixture'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            tasks.register<CreateS3BucketTask>("createBucket") {
                bucketName.set("fixture-bucket")
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('createBucket', CreateS3BucketTask) {
                bucketName.set('fixture-bucket')
            }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - S3 tasks"), output);
        assertTrue(output.contains("createBucket - Creates or reuses an S3 bucket in AWS Emulator."), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void rejectsInvalidBucketInputsBeforeStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 's3-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('badBucket', CreateS3BucketTask) { bucketName.set('InvalidBucket') }
            """);
        assertTrue(runner(project, "badBucket").buildAndFail().getOutput().contains("bucketName must"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void moduleConfiguresAllTaskTypesAndRegistersDevelopmentCommandsLazily() throws Exception {
        fixture("""
            val buckets = tasks.register<CreateS3BucketsTask>("createBuckets") { buckets.add("fixture-bucket") }
            tasks.register<ListS3BucketsTask>("customListBuckets")
            tasks.register<PurgeS3BucketsTask>("customPurgeBuckets")
            tasks.register<DeleteS3BucketsTask>("customDeleteBuckets")
            check(buckets.get().mode.get() == EmulatorMode.MANAGED)
            listOf("listS3Buckets", "purgeS3Buckets", "deleteS3Buckets").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            check(!tasks.named<DeleteS3BucketsTask>("deleteS3Buckets").get().force.get())
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        for (String task : new String[] {"createBuckets", "customListBuckets", "customPurgeBuckets", "customDeleteBuckets",
            "listS3Buckets", "purgeS3Buckets", "deleteS3Buckets"}) {
            assertTrue(output.contains(task + " - "), output);
        }
        assertTrue(output.contains("AWS Emulator - S3 tasks"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void validatesBulkAndCommandLineInputsBeforeStartingDocker() throws Exception {
        fixture("""
            tasks.register<CreateS3BucketsTask>("badBuckets") { buckets.set(listOf("valid-bucket", "InvalidBucket")) }
            """);
        String output = runner(project, "badBuckets").buildAndFail().getOutput();
        assertTrue(output.contains("bucketName must"), output);
        String cli = runner(project, "purgeS3Buckets", "--buckets=valid-bucket,").buildAndFail().getOutput();
        assertTrue(cli.contains("bucketName must"), cli);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(String tasks) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"s3-fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            """ + tasks);
    }
}
