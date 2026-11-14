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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbTableDriftTest {
    private static final String KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/default-key";

    @Test
    void acceptsEquivalentDefinitionsRegardlessOfOrderingAndGeneratedMetadata() {
        var request = request().toBuilder().globalSecondaryIndexes(global("by-category"), global("by-other"))
            .localSecondaryIndexes(local("by-price"), local("by-other-price")).build();
        var table = table().toBuilder().keySchema(key("created", KeyType.RANGE), key("id", KeyType.HASH))
            .attributeDefinitions(attribute("price", ScalarAttributeType.N), attribute("category", ScalarAttributeType.S),
                attribute("created", ScalarAttributeType.S), attribute("id", ScalarAttributeType.S))
            .globalSecondaryIndexes(describe(global("by-other")), describe(global("by-category")).toBuilder()
                .projection(Projection.builder().projectionType(ProjectionType.INCLUDE).nonKeyAttributes("title", "price").build())
                .indexStatus(IndexStatus.ACTIVE).indexArn("generated-index-arn").indexSizeBytes(42L).itemCount(3L)
                .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(5L).writeCapacityUnits(6L)
                    .lastIncreaseDateTime(Instant.now()).numberOfDecreasesToday(2L).build()).build())
            .localSecondaryIndexes(describe(local("by-other-price")), describe(local("by-price")))
            .billingModeSummary(BillingModeSummary.builder().billingMode(BillingMode.PROVISIONED).build())
            .tableArn("generated-table-arn").tableId("generated-id").itemCount(3L).creationDateTime(Instant.now())
            .latestStreamArn("generated-stream-arn").latestStreamLabel("generated-stream-label").build();
        assertDoesNotThrow(() -> validate(request, table));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("mismatchedTables")
    void identifiesEachMismatchedSetting(TableDescription table, String detail) {
        String message = assertThrows(GradleException.class, () -> validate(request(), table)).getMessage();
        assertTrue(message.contains("DynamoDB table 'catalog.products' configuration mismatch:"), message);
        assertTrue(message.contains(detail), message);
        assertTrue(message.contains("The existing table and its items were not changed."), message);
        assertTrue(message.contains("Align the task definition"), message);
    }

    static Stream<Arguments> mismatchedTables() {
        return Stream.of(
            Arguments.of(table().toBuilder().keySchema(key("other-id", KeyType.HASH), key("created", KeyType.RANGE)).build(),
                "keySchema: requested {HASH=id, RANGE=created}, existing {HASH=other-id, RANGE=created}"),
            Arguments.of(table().toBuilder().attributeDefinitions(attribute("id", ScalarAttributeType.N)).build(),
                "attributeDefinitions: requested {category=S, created=S, id=S, price=N}, existing {id=N}"),
            Arguments.of(table().toBuilder().billingModeSummary(BillingModeSummary.builder().billingMode(BillingMode.PAY_PER_REQUEST).build()).build(),
                "billingMode: requested PROVISIONED, existing PAY_PER_REQUEST"),
            Arguments.of(table().toBuilder().provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(2L).writeCapacityUnits(3L).build()).build(),
                "provisionedThroughput.readCapacityUnits: requested 100, existing 2"),
            Arguments.of(table().toBuilder().provisionedThroughput((ProvisionedThroughputDescription) null).build(),
                "provisionedThroughput.writeCapacityUnits: requested 100, existing <not reported>"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(List.of()).build(),
                "globalSecondaryIndexes[by-category]: requested present, existing absent"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(describe(global("by-category")), describe(global("unexpected"))).build(),
                "globalSecondaryIndexes[unexpected]: requested absent, existing present"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(describe(global("by-category")).toBuilder().keySchema(key("id", KeyType.HASH)).build()).build(),
                "globalSecondaryIndexes[by-category].keySchema: requested {HASH=category}, existing {HASH=id}"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(describe(global("by-category")).toBuilder()
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build()).build(),
                "globalSecondaryIndexes[by-category].projection.projectionType: requested INCLUDE, existing ALL"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(describe(global("by-category")).toBuilder()
                .projection(Projection.builder().projectionType(ProjectionType.INCLUDE).nonKeyAttributes("other").build()).build()).build(),
                "globalSecondaryIndexes[by-category].projection.nonKeyAttributes: requested [price, title], existing [other]"),
            Arguments.of(table().toBuilder().globalSecondaryIndexes(describe(global("by-category")).toBuilder()
                .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(7L).writeCapacityUnits(8L).build()).build()).build(),
                "globalSecondaryIndexes[by-category].provisionedThroughput.writeCapacityUnits: requested 6, existing 8"),
            Arguments.of(table().toBuilder().localSecondaryIndexes(List.of()).build(),
                "localSecondaryIndexes[by-price]: requested present, existing absent"),
            Arguments.of(table().toBuilder().localSecondaryIndexes(describe(local("by-price")), describe(local("unexpected"))).build(),
                "localSecondaryIndexes[unexpected]: requested absent, existing present"),
            Arguments.of(table().toBuilder().localSecondaryIndexes(describe(local("by-price")).toBuilder()
                .keySchema(key("id", KeyType.HASH), key("created", KeyType.RANGE)).build()).build(),
                "localSecondaryIndexes[by-price].keySchema: requested {HASH=id, RANGE=price}, existing {HASH=id, RANGE=created}"),
            Arguments.of(table().toBuilder().localSecondaryIndexes(describe(local("by-price")).toBuilder()
                .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build()).build()).build(),
                "localSecondaryIndexes[by-price].projection.projectionType: requested ALL, existing KEYS_ONLY"),
            Arguments.of(table().toBuilder().streamSpecification(StreamSpecification.builder().streamEnabled(false).build()).build(),
                "streamSpecification.streamEnabled: requested true, existing false"),
            Arguments.of(table().toBuilder().streamSpecification(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.OLD_IMAGE).build()).build(),
                "streamSpecification.streamViewType: requested NEW_IMAGE, existing OLD_IMAGE"),
            Arguments.of(table().toBuilder().sseDescription((SSEDescription) null).build(),
                "sseSpecification.enabled: requested true, existing false"),
            Arguments.of(table().toBuilder().sseDescription(SSEDescription.builder().status(SSEStatus.DISABLED).build()).build(),
                "sseSpecification.enabled: requested true, existing false"),
            Arguments.of(table().toBuilder().sseDescription(SSEDescription.builder().status(SSEStatus.ENABLED).sseType(SSEType.AES256).kmsMasterKeyArn(KEY_ARN).build()).build(),
                "sseSpecification.sseType: requested KMS, existing AES256"),
            Arguments.of(table().toBuilder().sseDescription(SSEDescription.builder().status(SSEStatus.ENABLED).sseType(SSEType.KMS).kmsMasterKeyArn("different-key").build()).build(),
                "sseSpecification.kmsMasterKeyId: requested alias/aws/dynamodb (" + KEY_ARN + "), existing different-key"),
            Arguments.of(table().toBuilder().sseDescription(SSEDescription.builder().status(SSEStatus.ENABLED).sseType(SSEType.KMS).build()).build(),
                "sseSpecification.kmsMasterKeyId: requested alias/aws/dynamodb (" + KEY_ARN + "), existing <not reported>")
        );
    }

    @Test
    void aggregatesMismatchesInAStableOrder() {
        var table = table().toBuilder().keySchema(key("other", KeyType.HASH))
            .localSecondaryIndexes(List.of()).streamSpecification((StreamSpecification) null).build();
        String message = assertThrows(GradleException.class, () -> validate(request(), table)).getMessage();
        assertTrue(message.indexOf("keySchema:") < message.indexOf("localSecondaryIndexes[by-price]:"), message);
        assertTrue(message.indexOf("localSecondaryIndexes[by-price]:") < message.indexOf("streamSpecification.streamEnabled:"), message);
        assertEquals(message, assertThrows(GradleException.class, () -> validate(request(), table)).getMessage());
    }

    @Test
    void normalizesOmittedAndDisabledStreamAndEncryptionSettings() {
        var request = request().toBuilder().streamSpecification((StreamSpecification) null).sseSpecification(SSESpecification.builder().build()).build();
        var table = table().toBuilder().streamSpecification((StreamSpecification) null).sseDescription((SSEDescription) null).build();
        assertDoesNotThrow(() -> DynamoDbTableDrift.validate(request, table, key -> { throw new AssertionError(key); }));
        var explicitRequest = request.toBuilder().streamSpecification(StreamSpecification.builder().streamEnabled(false).build())
            .sseSpecification(SSESpecification.builder().enabled(false).build()).build();
        // A disabled stream may retain its old view type. It is not a live configuration difference.
        var explicitTable = table.toBuilder().streamSpecification(StreamSpecification.builder().streamEnabled(false).streamViewType(StreamViewType.OLD_IMAGE).build())
            .sseDescription(SSEDescription.builder().status(SSEStatus.DISABLED).build()).build();
        assertDoesNotThrow(() -> validate(explicitRequest, explicitTable));
        assertDoesNotThrow(() -> validate(request.toBuilder().sseSpecification((SSESpecification) null).build(), table));
    }

    @Test
    void refusesUnexpectedStreamsEncryptionAndIndexesWhenDefaultsRequestNone() {
        var request = request().toBuilder().streamSpecification((StreamSpecification) null)
            .sseSpecification(SSESpecification.builder().enabled(false).build())
            .globalSecondaryIndexes(List.of()).localSecondaryIndexes(List.of()).build();
        String message = assertThrows(GradleException.class, () -> validate(request, table())).getMessage();
        assertTrue(message.contains("streamSpecification.streamEnabled: requested false, existing true"), message);
        assertTrue(message.contains("sseSpecification.enabled: requested false, existing true"), message);
        assertTrue(message.contains("globalSecondaryIndexes[by-category]: requested absent, existing present"), message);
        assertTrue(message.contains("localSecondaryIndexes[by-price]: requested absent, existing present"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default-key", "alias/products", "arn:aws:kms:us-east-1:123456789012:alias/products", "alias/aws/dynamodb"})
    void resolvesKeyIdsAndAliasesBeforeComparingArns(String identifier) {
        var calls = new AtomicInteger();
        var request = request().toBuilder().sseSpecification(SSESpecification.builder().enabled(true).sseType(SSEType.KMS).kmsMasterKeyId(identifier).build()).build();
        assertDoesNotThrow(() -> DynamoDbTableDrift.validate(request, table(), key -> {
            assertEquals(identifier, key);
            calls.incrementAndGet();
            return KEY_ARN;
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void comparesExplicitKeyArnsWithoutAKeyLookup() {
        var request = request().toBuilder().sseSpecification(SSESpecification.builder().enabled(true).kmsMasterKeyId(KEY_ARN).build()).build();
        assertDoesNotThrow(() -> DynamoDbTableDrift.validate(request, table(), key -> { throw new AssertionError(key); }));
        var different = request.toBuilder().sseSpecification(SSESpecification.builder().enabled(true).kmsMasterKeyId(KEY_ARN + "-other").build()).build();
        String message = assertThrows(GradleException.class, () -> validate(different, table())).getMessage();
        assertTrue(message.contains("sseSpecification.kmsMasterKeyId: requested " + KEY_ARN + "-other, existing " + KEY_ARN), message);
    }

    @Test
    void reportsUnresolvableEncryptionKeysWithoutSilentlyAcceptingThem() {
        var failure = new IllegalStateException("KMS unavailable");
        var error = assertThrows(GradleException.class, () -> DynamoDbTableDrift.validate(request(), table(), key -> { throw failure; }));
        assertSame(failure, error.getCause());
        assertTrue(error.getMessage().contains("Cannot verify DynamoDB table 'catalog.products' sseSpecification.kmsMasterKeyId"));
        assertTrue(error.getMessage().contains("could not resolve 'alias/aws/dynamodb' using KMS"));
        var missing = assertThrows(GradleException.class, () -> DynamoDbTableDrift.validate(request(), table(), key -> null));
        assertEquals("KMS returned no key ARN.", missing.getCause().getMessage());
    }

    @Test
    void reportsMissingTableMetadata() {
        assertEquals("DynamoDB returned no table description for: catalog.products",
            assertThrows(GradleException.class, () -> validate(request(), null)).getMessage());
    }

    private static void validate(CreateTableRequest request, TableDescription table) {
        DynamoDbTableDrift.validate(request, table, key -> {
            assertEquals("alias/aws/dynamodb", key);
            return KEY_ARN;
        });
    }

    private static CreateTableRequest request() {
        return CreateTableRequest.builder().tableName("catalog.products")
            .keySchema(key("id", KeyType.HASH), key("created", KeyType.RANGE))
            .attributeDefinitions(attribute("id", ScalarAttributeType.S), attribute("created", ScalarAttributeType.S),
                attribute("category", ScalarAttributeType.S), attribute("price", ScalarAttributeType.N))
            .globalSecondaryIndexes(global("by-category")).localSecondaryIndexes(local("by-price"))
            .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build())
            .sseSpecification(SSESpecification.builder().enabled(true).build())
            .streamSpecification(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build()).build();
    }

    private static TableDescription table() {
        return TableDescription.builder().tableName("catalog.products").tableStatus(TableStatus.ACTIVE)
            .keySchema(key("id", KeyType.HASH), key("created", KeyType.RANGE))
            .attributeDefinitions(attribute("id", ScalarAttributeType.S), attribute("created", ScalarAttributeType.S),
                attribute("category", ScalarAttributeType.S), attribute("price", ScalarAttributeType.N))
            .globalSecondaryIndexes(describe(global("by-category"))).localSecondaryIndexes(describe(local("by-price")))
            .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(100L).writeCapacityUnits(100L).build())
            .sseDescription(SSEDescription.builder().status(SSEStatus.ENABLED).sseType(SSEType.KMS).kmsMasterKeyArn(KEY_ARN).build())
            .streamSpecification(StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build()).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static GlobalSecondaryIndex global(String name) {
        return GlobalSecondaryIndex.builder().indexName(name).keySchema(key("category", KeyType.HASH))
            .projection(Projection.builder().projectionType(ProjectionType.INCLUDE).nonKeyAttributes("price", "title").build())
            .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(6L).build()).build();
    }

    private static LocalSecondaryIndex local(String name) {
        return LocalSecondaryIndex.builder().indexName(name).keySchema(key("id", KeyType.HASH), key("price", KeyType.RANGE))
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build();
    }

    private static GlobalSecondaryIndexDescription describe(GlobalSecondaryIndex index) {
        return GlobalSecondaryIndexDescription.builder().indexName(index.indexName()).keySchema(index.keySchema()).projection(index.projection())
            .provisionedThroughput(ProvisionedThroughputDescription.builder().readCapacityUnits(index.provisionedThroughput().readCapacityUnits())
                .writeCapacityUnits(index.provisionedThroughput().writeCapacityUnits()).build()).build();
    }

    private static LocalSecondaryIndexDescription describe(LocalSecondaryIndex index) {
        return LocalSecondaryIndexDescription.builder().indexName(index.indexName()).keySchema(index.keySchema()).projection(index.projection()).build();
    }
}
