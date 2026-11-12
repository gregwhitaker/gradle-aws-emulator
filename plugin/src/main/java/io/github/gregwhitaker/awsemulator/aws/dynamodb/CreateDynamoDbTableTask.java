/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
