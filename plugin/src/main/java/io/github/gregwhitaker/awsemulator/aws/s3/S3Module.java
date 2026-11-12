package io.github.gregwhitaker.awsemulator.aws.s3;

import org.gradle.api.Project;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;

/** Configures the S3 task group, including tasks registered by consuming builds. */
public final class S3Module {
    private static final String GROUP_NAME = "AWS Emulator - S3";

    private S3Module() {}

    public static void load(Project project) {
        configure(project, CreateS3BucketTask.class, "Creates or reuses an S3 bucket in AWS Emulator.");
        configure(project, CreateS3BucketsTask.class, "Creates or reuses S3 buckets in AWS Emulator.");
        configure(project, ListS3BucketsTask.class, "Lists S3 buckets in AWS Emulator.");
        configure(project, PurgeS3BucketsTask.class, "Removes all objects, versions, and delete markers from selected S3 buckets.");
        configure(project, DeleteS3BucketsTask.class, "Deletes selected S3 buckets from AWS Emulator.");

        project.getTasks().register("listS3Buckets", ListS3BucketsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("purgeS3Buckets", PurgeS3BucketsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteS3Buckets", DeleteS3BucketsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
        });
    }
}
