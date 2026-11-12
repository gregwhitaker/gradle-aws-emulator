package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.gradle.api.Project;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;

/** Configures the SQS task group, including tasks registered by consuming builds. */
public final class SqsModule {
    private static final String GROUP_NAME = "AWS Emulator - SQS";

    private SqsModule() {}

    public static void load(Project project) {
        configure(project, CreateSqsQueueTask.class, "Creates or reuses an SQS queue in AWS Emulator.");
        configure(project, CreateSqsQueuesTask.class, "Creates or reuses SQS queues in AWS Emulator.");
        configure(project, CreateSqsQueueWithDlqTask.class, "Creates or updates an SQS queue with a deadletter queue in AWS Emulator.");
        configure(project, ListSqsQueuesTask.class, "Lists SQS queues and message counts in AWS Emulator.");
        configure(project, PurgeSqsQueuesTask.class, "Requests removal of all messages from selected SQS queues.");
        configure(project, PublishSqsTask.class, "Publishes message files to selected SQS queues in AWS Emulator.");

        project.getTasks().register("listSqsQueues", ListSqsQueuesTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("purgeSqsQueues", PurgeSqsQueuesTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("publishSqs", PublishSqsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
        });
    }
}
