package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Deletes the selected table and its items, waiting until the table no longer exists. */
@DisableCachingByDefault(because = "Deletes live emulator resources")
public abstract class DeleteDynamoDbTableTask extends AbstractDynamoDbTableTask {
    @TaskAction
    public void deleteTable() {
        String name = tableName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).dynamoDb()) {
            new DynamoDbTableOperations().delete(client, name, getWaitTimeoutSeconds().get());
        }
        getLogger().lifecycle("Deleted DynamoDB table: {}", name);
    }
}
