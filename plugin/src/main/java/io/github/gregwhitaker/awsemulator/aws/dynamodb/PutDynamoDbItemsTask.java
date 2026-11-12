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
