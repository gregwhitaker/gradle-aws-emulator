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

/**
 * Creates a table, waits for ACTIVE, and optionally initializes a newly created table.
 * Reuses existing tables only when their schema, indexes, capacity, encryption, and streams
 * match the requested definition; configuration drift fails without modifying the table.
 */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateDynamoDbTableTask extends AbstractDynamoDbTableTask {
    public CreateDynamoDbTableTask() {
        getGlobalSecondaryIndexes().convention(List.of());
        getLocalSecondaryIndexes().convention(List.of());
        getProvisionedThroughput().convention(ProvisionedThroughput.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build());
        getSseSpecification().convention(SSESpecification.builder().enabled(true).build());
    }

    /** Required primary key schema; compared by key role when reusing a table. */
    @Input public abstract ListProperty<KeySchemaElement> getKeySchema();
    /** Required key attribute types; compared by attribute name when reusing a table. */
    @Input public abstract ListProperty<AttributeDefinition> getAttributeDefinitions();
    /** Complete set of global indexes, including projections and capacity. Defaults to empty. */
    @Input public abstract ListProperty<GlobalSecondaryIndex> getGlobalSecondaryIndexes();
    /** Complete set of local indexes, including projections. Defaults to empty. */
    @Input public abstract ListProperty<LocalSecondaryIndex> getLocalSecondaryIndexes();
    /** Provisioned read/write capacity. Defaults to 100 units each. */
    @Input public abstract Property<ProvisionedThroughput> getProvisionedThroughput();
    /**
     * Encryption settings for creation and reuse. Defaults to enabled=true (KMS with alias/aws/dynamodb).
     * Key IDs and aliases are resolved through the selected emulator's KMS client when checking reuse.
     */
    @Input public abstract Property<SSESpecification> getSseSpecification();
    /** Stream settings for creation and reuse. Unset means streams must be disabled. */
    @Input @Optional public abstract Property<StreamSpecification> getStreamSpecification();
    /** Build-classpath class with a public DynamoDbClient constructor and a public void run() method. */
    @Input @Optional public abstract Property<String> getInitializer();

    @TaskAction
    public void createTable() {
        String name = tableName();
        var requestBuilder = CreateTableRequest.builder().tableName(name).keySchema(getKeySchema().get())
            .attributeDefinitions(getAttributeDefinitions().get()).provisionedThroughput(getProvisionedThroughput().get())
            .sseSpecification(getSseSpecification().get()).streamSpecification(getStreamSpecification().getOrNull());
        // DynamoDB requires optional index lists to be absent when there are no indexes.
        if (!getGlobalSecondaryIndexes().get().isEmpty()) requestBuilder.globalSecondaryIndexes(getGlobalSecondaryIndexes().get());
        if (!getLocalSecondaryIndexes().get().isEmpty()) requestBuilder.localSecondaryIndexes(getLocalSecondaryIndexes().get());
        var request = requestBuilder.build();
        DynamoDbTableDefinition.validate(request);
        var initializer = getInitializer().isPresent()
            ? DynamoDbInitializerExecutor.resolve(getInitializer().get(), getClass().getClassLoader()) : null;
        var clients = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get());
        try (var client = clients.dynamoDb()) {
            boolean created = new DynamoDbTableOperations().create(client, request, getWaitTimeoutSeconds().get(), key -> {
                try (var kms = clients.kms()) {
                    var metadata = kms.describeKey(builder -> builder.keyId(key)).keyMetadata();
                    return metadata == null ? null : metadata.arn();
                }
            });
            if (created && initializer != null) {
                initializer.invoke(client);
                getLogger().lifecycle("Initialized DynamoDB table: {}", name);
            }
            getLogger().lifecycle("DynamoDB table ready: {} ({})", name, created ? "created" : "reused");
        }
    }
}
