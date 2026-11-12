package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers the DynamoDB commands and configures custom tasks in the same service group. */
public final class DynamoDbModule {
    private static final String GROUP_NAME = "AWS Emulator - DynamoDB";

    private DynamoDbModule() {}

    public static void load(Project project) {
        configure(project, CreateDynamoDbTableTask.class, "Creates or reuses a DynamoDB table in AWS Emulator.");
        configure(project, DeleteDynamoDbTableTask.class, "Deletes a DynamoDB table from AWS Emulator.");
        configure(project, ListDynamoDBTablesTask.class, "Lists DynamoDB tables in AWS Emulator.");

        project.getTasks().register("deleteDynamoDbTable", DeleteDynamoDbTableTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("listDynamoDbTables", ListDynamoDBTablesTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
        });
    }
}
