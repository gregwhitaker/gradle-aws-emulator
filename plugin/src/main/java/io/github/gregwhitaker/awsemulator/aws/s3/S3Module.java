package io.github.gregwhitaker.awsemulator.aws.s3;

import org.gradle.api.Project;

/** Configures the S3 task group, including tasks registered by consuming builds. */
public final class S3Module {
    private static final String GROUP_NAME = "AWS Emulator - S3";

    private S3Module() {}

    public static void load(Project project) {
        project.getTasks().withType(CreateS3BucketTask.class).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription("Creates or reuses an S3 bucket in AWS Emulator.");
        });
    }
}
