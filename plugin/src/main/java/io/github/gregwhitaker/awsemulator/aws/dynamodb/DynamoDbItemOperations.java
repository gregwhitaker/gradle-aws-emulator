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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.*;

/** Bounded batch retries and key-only table clearing. */
final class DynamoDbItemOperations {
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final Sleeper sleeper;
    DynamoDbItemOperations() { this(Thread::sleep); }
    DynamoDbItemOperations(Sleeper sleeper) { this.sleeper = sleeper; }

    void put(DynamoDbClient client, String table, List<Map<String, AttributeValue>> items, int maxRetries, int delayMillis) {
        var description = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).table();
        List<String> keys = keyNames(description);
        Set<Map<String, AttributeValue>> seen = new HashSet<>();
        for (var item : items) {
            Map<String, AttributeValue> key = key(item, keys);
            if (!seen.add(key)) throw new GradleException("DynamoDB fixture contains duplicate primary keys (values omitted).");
        }
        var requests = items.stream().map(item -> WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build()).toList();
        write(client, table, requests, maxRetries, delayMillis);
    }

    int purge(DynamoDbClient client, String table, int maxRetries, int delayMillis) {
        List<String> names = keyNames(client.describeTable(DescribeTableRequest.builder().tableName(table).build()).table());
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) aliases.put("#key" + i, names.get(i));
        List<WriteRequest> requests = new ArrayList<>();
        Set<Map<String, AttributeValue>> tokens = new HashSet<>();
        Map<String, AttributeValue> token = null;
        // Finish scanning before deleting: emulator continuation tokens may depend on existing items.
        do {
            var page = client.scan(ScanRequest.builder().tableName(table).consistentRead(true).limit(1000)
                .projectionExpression(String.join(", ", aliases.keySet())).expressionAttributeNames(aliases)
                .exclusiveStartKey(token).build());
            for (var item : page.items()) {
                requests.add(WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(key(item, names)).build()).build());
            }
            token = page.lastEvaluatedKey();
            if (token != null && !token.isEmpty() && !tokens.add(token)) {
                throw new GradleException("DynamoDB scan returned a repeated continuation key.");
            }
        } while (token != null && !token.isEmpty());
        write(client, table, requests, maxRetries, delayMillis);
        return requests.size();
    }

    private List<String> keyNames(TableDescription description) {
        if (description == null || description.keySchema().isEmpty()) throw new GradleException("DynamoDB returned no table key schema.");
        return description.keySchema().stream().map(KeySchemaElement::attributeName).toList();
    }

    private Map<String, AttributeValue> key(Map<String, AttributeValue> item, List<String> names) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        for (String name : names) {
            AttributeValue value = item.get(name);
            if (value == null || value.s() == null && value.n() == null && value.b() == null
                    || value.s() != null && value.s().isEmpty() || value.b() != null && value.b().asByteArray().length == 0) {
                throw new GradleException("DynamoDB item requires each primary key attribute as a nonempty S, N, or B value (values omitted).");
            }
            if (value.n() != null) value = AttributeValue.builder().n(DynamoDbItemFixtures.canonicalNumber(value.n())).build();
            key.put(name, value);
        }
        return key;
    }

    private void write(DynamoDbClient client, String table, List<WriteRequest> requests, int maxRetries, int delayMillis) {
        for (int start = 0; start < requests.size(); start += 25) {
            List<WriteRequest> pending = requests.subList(start, Math.min(start + 25, requests.size()));
            for (int attempt = 0; ; attempt++) {
                var response = client.batchWriteItem(BatchWriteItemRequest.builder().requestItems(Map.of(table, pending)).build());
                pending = response.unprocessedItems().getOrDefault(table, List.of());
                if (pending.isEmpty()) break;
                if (attempt >= maxRetries) throw new GradleException("DynamoDB batch still has " + pending.size()
                    + " unprocessed items after " + maxRetries + " retries; earlier writes may have succeeded.");
                try { sleeper.sleep(Math.min(2000L, (long) delayMillis * (1L << Math.min(attempt, 16)))); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GradleException("Interrupted while retrying DynamoDB items.", e);
                }
            }
        }
    }
}
