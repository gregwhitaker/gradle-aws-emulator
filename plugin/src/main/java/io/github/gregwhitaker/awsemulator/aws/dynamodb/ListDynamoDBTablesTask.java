package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists every table in the selected emulator, following all response pages. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListDynamoDBTablesTask extends AbstractAwsResourceTask {
    @TaskAction
    public void listTables() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).dynamoDb()) {
            var tables = new DynamoDbTableOperations().list(client);
            getLogger().lifecycle("TableName");
            tables.forEach(name -> getLogger().lifecycle("{}", name));
            if (tables.isEmpty()) getLogger().lifecycle("No Tables Found!");
        }
    }
}
