package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Imports a JSON array of DynamoDB attribute maps; existing items with matching keys are replaced. */
@DisableCachingByDefault(because = "Writes live emulator items")
public abstract class PutDynamoDbItemsTask extends AbstractDynamoDbItemsTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getItemsFile();

    @TaskAction public void putItems() {
        String table = tableName();
        var items = new DynamoDbItemFixtures().read(getItemsFile().get().getAsFile().toPath());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).dynamoDb()) {
            new DynamoDbItemOperations().put(client, table, items, getMaxRetries().get(), getRetryDelayMillis().get());
        }
        getLogger().lifecycle("Loaded {} DynamoDB items into {}", items.size(), table);
    }
}
