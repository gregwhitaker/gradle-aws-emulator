package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.List;

/** Creates a table, waits for ACTIVE, and optionally initializes a newly created table. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateDynamoDbTableTask extends AbstractDynamoDbTableTask {
    public CreateDynamoDbTableTask() {
        getGlobalSecondaryIndexes().convention(List.of());
        getLocalSecondaryIndexes().convention(List.of());
        getProvisionedThroughput().convention(ProvisionedThroughput.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build());
        getSseSpecification().convention(SSESpecification.builder().enabled(true).build());
    }

    @Input public abstract ListProperty<KeySchemaElement> getKeySchema();
    @Input public abstract ListProperty<AttributeDefinition> getAttributeDefinitions();
    @Input public abstract ListProperty<GlobalSecondaryIndex> getGlobalSecondaryIndexes();
    @Input public abstract ListProperty<LocalSecondaryIndex> getLocalSecondaryIndexes();
    /** Provisioned read/write capacity. Defaults to 100 units each. */
    @Input public abstract Property<ProvisionedThroughput> getProvisionedThroughput();
    /** SDK encryption settings sent when creating the table. Defaults to enabled=true. */
    @Input public abstract Property<SSESpecification> getSseSpecification();
    @Input @Optional public abstract Property<StreamSpecification> getStreamSpecification();
    /** Build-classpath class with a public DynamoDbClient constructor and a public void run() method. */
    @Input @Optional public abstract Property<String> getInitializer();

    @TaskAction
    public void createTable() {
        String name = tableName();
        var request = CreateTableRequest.builder().tableName(name).keySchema(getKeySchema().get())
            .attributeDefinitions(getAttributeDefinitions().get()).globalSecondaryIndexes(getGlobalSecondaryIndexes().get())
            .localSecondaryIndexes(getLocalSecondaryIndexes().get()).provisionedThroughput(getProvisionedThroughput().get())
            .sseSpecification(getSseSpecification().get()).streamSpecification(getStreamSpecification().getOrNull()).build();
        DynamoDbTableDefinition.validate(request);
        var initializer = getInitializer().isPresent()
            ? DynamoDbInitializerExecutor.resolve(getInitializer().get(), getClass().getClassLoader()) : null;
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).dynamoDb()) {
            boolean created = new DynamoDbTableOperations().create(client, request, getWaitTimeoutSeconds().get());
            if (created && initializer != null) {
                initializer.invoke(client);
                getLogger().lifecycle("Initialized DynamoDB table: {}", name);
            }
            getLogger().lifecycle("DynamoDB table ready: {} ({})", name, created ? "created" : "reused");
        }
    }
}
