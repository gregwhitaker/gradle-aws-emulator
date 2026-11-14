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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/**
 * Exports all items from an existing table as DynamoDB AttributeValue JSON for PutDynamoDbItemsTask.
 * Requires an existing runtime; never starts an owned runtime. Excludes table configuration.
 * Stop writers while exporting: even consistent scans do not provide snapshot isolation.
 */
@DisableCachingByDefault(because = "Reads live emulator items")
public abstract class ExportDynamoDbItemsTask extends AbstractAwsResourceTask {
    /** Explicit table whose items will be exported. */
    @Input public abstract Property<String> getTableName();
    /** JSON array, including an empty array for an empty table; maximum 16 MiB. Contains item data. */
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void exportItems() {
        String table = getTableName().get();
        DynamoDbTableDefinition.validateName(table);
        if (getApiCallTimeoutSeconds().get() < 1) throw new GradleException("apiCallTimeoutSeconds must be positive.");
        var connection = getRuntime().get().inspectionConnection(getMode().get());
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).dynamoDb()) {
            int count = new DynamoDbItemExport().export(client, table, getOutputFile().get().getAsFile().toPath());
            getLogger().lifecycle("Exported {} DynamoDB items from {}", count, table);
        }
    }
}
