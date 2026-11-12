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
