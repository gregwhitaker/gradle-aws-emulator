package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.gradle.api.Project;

/** Configures the SQS task group, including tasks registered by consuming builds. */
public final class SqsModule {
    private static final String GROUP_NAME = "AWS Emulator - SQS";

    private SqsModule() {}

    public static void load(Project project) {
        project.getTasks().withType(CreateSqsQueueTask.class).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription("Creates or reuses an SQS queue in AWS Emulator.");
        });
    }
}
