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

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbItemOperationsTest {
    @Test void sendsAtMost25ItemsAndRetriesOnlyUnprocessedWrites() {
        var calls = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        var items = IntStream.range(0, 27).mapToObj(i -> item("" + i)).toList();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            if (method.getName().equals("describeTable")) return description();
            assertEquals("batchWriteItem", method.getName());
            var requests = ((BatchWriteItemRequest) args[0]).requestItems().get("products");
            int call = calls.getAndIncrement();
            if (call == 0) {
                assertEquals(25, requests.size());
                return BatchWriteItemResponse.builder().unprocessedItems(Map.of("products", requests.subList(22, 25))).build();
            }
            assertEquals(call == 1 ? 3 : 2, requests.size());
            assertEquals(call == 1 ? "22" : "25", requests.get(0).putRequest().item().get("id").s());
            return BatchWriteItemResponse.builder().build();
        });
        new DynamoDbItemOperations(delays::add).put(client, "products", items, 8, 100);
        assertEquals(3, calls.get());
        assertEquals(List.of(100L), delays);
    }

    @Test void boundsRetriesAndDoesNotDiscloseItemValues() {
        var calls = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            if (method.getName().equals("describeTable")) return description();
            calls.incrementAndGet();
            return BatchWriteItemResponse.builder().unprocessedItems(((BatchWriteItemRequest) args[0]).requestItems()).build();
        });
        var error = assertThrows(GradleException.class, () -> new DynamoDbItemOperations(delays::add)
            .put(client, "products", List.of(item("private-value")), 3, 800));
        assertEquals(4, calls.get());
        assertEquals(List.of(800L, 1600L, 2000L), delays);
        assertTrue(error.getMessage().contains("earlier writes may have succeeded"));
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test void validatesAllKeysBeforeAnyWriteIncludingNumericallyEquivalentKeys() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return description();
        });
        var operations = new DynamoDbItemOperations();
        assertThrows(GradleException.class, () -> operations.put(client, "products", List.of(item("1"), Map.of()), 0, 1));
        var one = Map.of("id", AttributeValue.builder().n("1").build());
        var same = Map.of("id", AttributeValue.builder().n("1.0").build());
        assertThrows(GradleException.class, () -> operations.put(client, "products", List.of(one, same), 0, 1));
    }

    @Test void clearsEveryScanPageUsingOnlyKeyAttributesAndLeavesTheTableIntact() {
        var pages = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> description();
            case "scan" -> {
                var request = (ScanRequest) args[0];
                assertTrue(request.consistentRead());
                assertEquals(Map.of("#key0", "id"), request.expressionAttributeNames());
                assertEquals("#key0", request.projectionExpression());
                if (pages.getAndIncrement() == 0) yield ScanResponse.builder().items(item("1")).lastEvaluatedKey(item("1")).build();
                assertEquals(item("1"), request.exclusiveStartKey());
                yield ScanResponse.builder().items(item("2")).build();
            }
            case "batchWriteItem" -> {
                assertEquals(2, pages.get());
                var writes = ((BatchWriteItemRequest) args[0]).requestItems().get("products");
                assertEquals(List.of(item("1"), item("2")), writes.stream().map(write -> write.deleteRequest().key()).toList());
                yield BatchWriteItemResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertEquals(2, new DynamoDbItemOperations().purge(client, "products", 0, 1));
    }

    @Test void rejectsRepeatedScanTokensBeforeDeletingAnything() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> description();
            case "scan" -> ScanResponse.builder().items(item("1")).lastEvaluatedKey(item("1")).build();
            default -> throw new AssertionError(method.getName());
        });
        assertThrows(GradleException.class, () -> new DynamoDbItemOperations().purge(client, "products", 0, 1));
    }

    private Map<String, AttributeValue> item(String id) { return Map.of("id", AttributeValue.builder().s(id).build()); }
    private DescribeTableResponse description() {
        return DescribeTableResponse.builder().table(TableDescription.builder()
            .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()).build()).build();
    }
}
