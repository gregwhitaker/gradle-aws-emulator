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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbTableOperationsTest {
    private final DynamoDbTableOperations operations = new DynamoDbTableOperations();
    private final CreateTableRequest request = CreateTableRequest.builder().tableName("catalog.products")
        .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
        .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build()).build();

    @Test
    void createsAMissingTableAndWaitsForActiveWithoutClosingTheSharedClient() {
        var describes = new AtomicInteger();
        var creates = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> {
                assertEquals(request.tableName(), ((DescribeTableRequest) args[0]).tableName());
                int call = describes.incrementAndGet();
                if (call == 1) throw missing();
                yield table(call == 2 ? TableStatus.CREATING : TableStatus.ACTIVE);
            }
            case "createTable" -> {
                assertSame(request, args[0]);
                creates.incrementAndGet();
                yield CreateTableResponse.builder().build();
            }
            default -> throw new AssertionError("Unexpected call: " + method.getName());
        });
        assertTrue(create(client, 5));
        assertEquals(3, describes.get());
        assertEquals(1, creates.get());
    }

    @Test
    void reusesAnActiveTableWithoutRecreatingOrUpdatingIt() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return table(TableStatus.ACTIVE);
        });
        assertFalse(create(client, 5));
    }

    @Test
    void waitsForAnExistingTableInTransitionWithoutClaimingInitialization() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return table(describes.incrementAndGet() == 1 ? TableStatus.UPDATING : TableStatus.ACTIVE);
        });
        assertFalse(create(client, 5));
        assertEquals(2, describes.get());
    }

    @Test
    void aConcurrentCreatorRetainsOwnershipOfInitialization() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> {
                if (describes.incrementAndGet() == 1) throw missing();
                yield table(TableStatus.ACTIVE);
            }
            case "createTable" -> throw ResourceInUseException.builder().build();
            default -> throw new AssertionError(method.getName());
        });
        assertFalse(create(client, 5));
        assertEquals(2, describes.get());
    }

    @Test
    void propagatesServiceErrorsInsteadOfTreatingThemAsMissingTables() {
        var failure = DynamoDbException.builder().statusCode(403).build();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            throw failure;
        });
        assertSame(failure, assertThrows(DynamoDbException.class, () -> create(client, 5)));
    }

    @Test
    void failsWhenADeletedTableCannotBecomeActive() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return table(TableStatus.DELETING);
        });
        assertThrows(RuntimeException.class, () -> create(client, 1));
    }

    @Test
    void boundsTheWaitForATableThatNeverBecomesActive() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> table(TableStatus.CREATING));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertThrows(RuntimeException.class, () -> create(client, 1)));
    }

    @Test
    void refusesToReuseAnActiveTableWithDriftWithoutMutatingIt() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return driftedTable(TableStatus.ACTIVE);
        });
        String message = assertThrows(GradleException.class, () -> create(client, 5)).getMessage();
        assertTrue(message.contains("DynamoDB table 'catalog.products' configuration mismatch"), message);
        assertTrue(message.contains("provisionedThroughput.readCapacityUnits: requested 100, existing 5"), message);
    }

    @Test
    void comparesTheFinalActiveDescriptionAfterWaiting() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return describes.incrementAndGet() == 1 ? driftedTable(TableStatus.UPDATING) : table(TableStatus.ACTIVE);
        });
        assertFalse(create(client, 5));
        assertEquals(2, describes.get());
    }

    @Test
    void detectsDriftIntroducedWhileWaiting() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("describeTable", method.getName());
            return describes.incrementAndGet() == 1 ? table(TableStatus.UPDATING) : driftedTable(TableStatus.ACTIVE);
        });
        assertTrue(assertThrows(GradleException.class, () -> create(client, 5)).getMessage().contains("configuration mismatch"));
        assertEquals(2, describes.get());
    }

    @Test
    void detectsDriftInATableCreatedByAConcurrentTask() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "describeTable" -> {
                if (describes.incrementAndGet() == 1) throw missing();
                yield driftedTable(TableStatus.ACTIVE);
            }
            case "createTable" -> throw ResourceInUseException.builder().build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        assertTrue(assertThrows(GradleException.class, () -> create(client, 5)).getMessage().contains("configuration mismatch"));
        assertEquals(2, describes.get());
    }

    @Test
    void deletionWaitsUntilTheTableDisappears() {
        var deletes = new AtomicInteger();
        var describes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "deleteTable" -> {
                assertEquals(request.tableName(), ((DeleteTableRequest) args[0]).tableName());
                deletes.incrementAndGet();
                yield DeleteTableResponse.builder().build();
            }
            case "describeTable" -> {
                assertEquals(1, deletes.get());
                if (describes.incrementAndGet() == 1) yield table(TableStatus.DELETING);
                throw missing();
            }
            default -> throw new AssertionError(method.getName());
        });
        operations.delete(client, request.tableName(), 5);
        assertEquals(2, describes.get());
    }

    @Test
    void deletingAMissingTableFailsClearlyAndOtherFailuresArePreserved() {
        var missing = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> { throw missing(); });
        assertDoesNotThrow(() -> operations.delete(missing, "missing-table", 5, true));
        assertTrue(assertThrows(GradleException.class, () -> operations.delete(missing, "missing-table", 5))
            .getMessage().contains("does not exist"));
        var failure = DynamoDbException.builder().statusCode(403).build();
        var denied = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> { throw failure; });
        assertSame(failure, assertThrows(DynamoDbException.class, () -> operations.delete(denied, "denied-table", 5)));
        assertSame(failure, assertThrows(DynamoDbException.class, () -> operations.delete(denied, "denied-table", 5, true)));
    }

    @Test
    void listsEveryPageIncludingAfterAnEmptyIntermediatePage() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("listTables", method.getName());
            var page = (ListTablesRequest) args[0];
            assertEquals(100, page.limit());
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertNull(page.exclusiveStartTableName());
                    yield ListTablesResponse.builder().tableNames("first").lastEvaluatedTableName("first").build();
                }
                case 2 -> {
                    assertEquals("first", page.exclusiveStartTableName());
                    yield ListTablesResponse.builder().lastEvaluatedTableName("middle").build();
                }
                case 3 -> {
                    assertEquals("middle", page.exclusiveStartTableName());
                    yield ListTablesResponse.builder().tableNames("last").build();
                }
                default -> throw new AssertionError("Unexpected page");
            };
        });
        assertEquals(List.of("first", "last"), operations.list(client));
    }

    @Test
    void refusesRepeatedPaginationTokens() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> ListTablesResponse.builder().lastEvaluatedTableName("repeat").build());
        assertThrows(GradleException.class, () -> operations.list(client));
    }

    private ResourceNotFoundException missing() {
        return ResourceNotFoundException.builder().statusCode(400)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ResourceNotFoundException").build()).build();
    }

    private DescribeTableResponse table(TableStatus status) {
        return DescribeTableResponse.builder().table(TableDescription.builder().tableName(request.tableName()).tableStatus(status)
            .keySchema(request.keySchema()).attributeDefinitions(request.attributeDefinitions())
            .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build()).build()).build();
    }

    private DescribeTableResponse driftedTable(TableStatus status) {
        return DescribeTableResponse.builder().table(table(status).table().toBuilder()
            .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(5L).writeCapacityUnits(100L).build()).build()).build();
    }

    private boolean create(DynamoDbClient client, int timeoutSeconds) {
        return operations.create(client, request, timeoutSeconds, key -> { throw new AssertionError("Unexpected KMS lookup: " + key); });
    }
}
