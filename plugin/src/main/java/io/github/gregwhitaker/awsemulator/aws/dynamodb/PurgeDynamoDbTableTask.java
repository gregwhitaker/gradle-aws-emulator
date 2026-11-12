package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Deletes all scanned items while preserving the table and its indexes. */
@DisableCachingByDefault(because = "Deletes live emulator items")
public abstract class PurgeDynamoDbTableTask extends AbstractDynamoDbItemsTask {
    @TaskAction public void purge() {
        String table = tableName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).dynamoDb()) {
            int count = new DynamoDbItemOperations().purge(client, table, getMaxRetries().get(), getRetryDelayMillis().get());
            getLogger().lifecycle("Removed {} DynamoDB items from {}", count, table);
        }
    }
}
