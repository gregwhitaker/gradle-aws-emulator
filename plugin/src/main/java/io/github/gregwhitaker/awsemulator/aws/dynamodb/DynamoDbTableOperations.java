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

import org.gradle.api.GradleException;
import software.amazon.awssdk.core.retry.backoff.FixedDelayBackoffStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Table lifecycle operations without Gradle project access or global SDK configuration. */
final class DynamoDbTableOperations {
    boolean create(DynamoDbClient client, CreateTableRequest request, int timeoutSeconds) {
        boolean created = false;
        try {
            var table = client.describeTable(DescribeTableRequest.builder().tableName(request.tableName()).build()).table();
            if (table != null && table.tableStatus() == TableStatus.ACTIVE) return false;
        } catch (ResourceNotFoundException missing) {
            try {
                client.createTable(request);
                created = true;
            } catch (ResourceInUseException raced) {
                // A concurrent creator owns initialization. Wait for its table without changing it.
            }
        }
        try (var waiter = waiter(client, timeoutSeconds)) {
            waiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(request.tableName()).build());
        }
        return created;
    }

    void delete(DynamoDbClient client, String name, int timeoutSeconds) {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(name).build());
        } catch (ResourceNotFoundException missing) {
            throw new GradleException("DynamoDB table does not exist: " + name, missing);
        }
        try (var waiter = waiter(client, timeoutSeconds)) {
            waiter.waitUntilTableNotExists(DescribeTableRequest.builder().tableName(name).build());
        }
    }

    List<String> list(DynamoDbClient client) {
        List<String> tables = new ArrayList<>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listTables(ListTablesRequest.builder().limit(100).exclusiveStartTableName(token).build());
            tables.addAll(page.tableNames());
            token = page.lastEvaluatedTableName();
            if (token != null && !token.isEmpty() && !tokens.add(token)) {
                throw new GradleException("DynamoDB listing returned a repeated continuation table name.");
            }
        } while (token != null && !token.isEmpty());
        return tables;
    }

    private DynamoDbWaiter waiter(DynamoDbClient client, int timeoutSeconds) {
        return DynamoDbWaiter.builder().client(client).overrideConfiguration(config -> config
            .waitTimeout(Duration.ofSeconds(timeoutSeconds)).maxAttempts(Integer.MAX_VALUE)
            .backoffStrategy(FixedDelayBackoffStrategy.create(Duration.ofMillis(500)))).build();
    }
}
