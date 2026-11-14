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
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbItemExportTest {
    @TempDir Path temp;

    @Test void exportsPaginatedTypedItemsAndReplaysWithoutLosingData() throws Exception {
        var fixtures = new DynamoDbItemFixtures();
        Path input = temp.resolve("original.json");
        Files.writeString(input, """
            [{"id":{"S":"b"},"price":{"N":"12345678901234567890.123456789"},
              "binary":{"B":"AAH/"},"enabled":{"BOOL":false},"nothing":{"NULL":true},
              "tags":{"SS":["b","a"]},"numbers":{"NS":["2","1.0"]},"blobs":{"BS":["AQ==","AA=="]},
              "nested":{"M":{"values":{"L":[{"S":""},{"N":"4"},{"M":{}},{"L":[]}]}}}},
             {"id":{"S":"a"}}]
            """);
        var items = fixtures.read(input);
        var calls = new AtomicInteger();
        var source = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> description();
            case "scan" -> {
                var request = (ScanRequest) args[0];
                assertEquals("products", request.tableName());
                assertTrue(request.consistentRead());
                if (calls.getAndIncrement() == 0) yield ScanResponse.builder().items(items.get(0))
                    .lastEvaluatedKey(Map.of("id", text("b"))).build();
                assertEquals(Map.of("id", text("b")), request.exclusiveStartKey());
                yield ScanResponse.builder().items(items.get(1)).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        Path output = temp.resolve("items.json");
        assertEquals(2, new DynamoDbItemExport().export(source, "products", output));
        var restored = fixtures.read(output);
        var b = restored.stream().filter(item -> item.get("id").s().equals("b")).findFirst().orElseThrow();
        assertEquals("12345678901234567890.123456789", b.get("price").n());
        assertEquals(Set.of("1", "2"), new HashSet<>(b.get("numbers").ns()));
        assertEquals(Set.of("a", "b"), new HashSet<>(b.get("tags").ss()));
        assertArrayEquals(new byte[] {0, 1, -1}, b.get("binary").b().asByteArray());
        assertFalse(b.get("enabled").bool());
        assertTrue(b.get("nothing").nul());
        assertTrue(b.get("nested").m().get("values").l().get(3).hasL());
        assertTrue(b.get("nested").m().get("values").l().get(3).l().isEmpty());
        assertTrue(b.get("nested").m().get("values").l().get(2).hasM());
        var writes = new AtomicInteger();
        var target = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            if (method.getName().equals("describeTable")) return description();
            assertEquals("batchWriteItem", method.getName());
            assertEquals(restored, ((BatchWriteItemRequest) args[0]).requestItems().get("products").stream()
                .map(write -> write.putRequest().item()).toList());
            writes.incrementAndGet();
            return BatchWriteItemResponse.builder().build();
        });
        new DynamoDbItemOperations().put(target, "products", restored, 0, 1);
        assertEquals(1, writes.get());
        byte[] first = Files.readAllBytes(output);
        calls.set(0);
        new DynamoDbItemExport().export(source, "products", output);
        assertArrayEquals(first, Files.readAllBytes(output));
    }

    @Test void exportsAndReplaysEmptyTables() throws Exception {
        var source = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> description();
            case "scan" -> ScanResponse.builder().build();
            default -> throw new AssertionError(method.getName());
        });
        Path output = temp.resolve("empty.json");
        assertEquals(0, new DynamoDbItemExport().export(source, "products", output));
        assertEquals("[]", Files.readString(output));
        var items = new DynamoDbItemFixtures().read(output);
        new DynamoDbItemOperations().put(source, "products", items, 0, 1);
    }

    @Test void failedRepeatedOrDuplicatePagesNeverReplacePreviousExport() throws Exception {
        Path output = temp.resolve("items.json");
        Files.writeString(output, "previous");
        for (int variant = 0; variant < 3; variant++) {
            int failure = variant;
            var source = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
                if (method.getName().equals("describeTable")) return description();
                if (failure == 0) throw ResourceNotFoundException.builder().build();
                var item = Map.of("id", text("private-value"));
                return failure == 1 ? ScanResponse.builder().lastEvaluatedKey(item).build()
                    : ScanResponse.builder().items(item, item).build();
            });
            var error = assertThrows(RuntimeException.class, () -> new DynamoDbItemExport().export(source, "products", output));
            assertFalse(error.toString().contains("private-value"));
            assertEquals("previous", Files.readString(output));
        }
    }

    private AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
    private DescribeTableResponse description() {
        return DescribeTableResponse.builder().table(TableDescription.builder()
            .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()).build()).build();
    }
}
