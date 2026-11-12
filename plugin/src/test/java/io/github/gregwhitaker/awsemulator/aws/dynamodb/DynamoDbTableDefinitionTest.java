package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbTableDefinitionTest {
    @Test
    void acceptsSimpleAndCompositeKeysWithGlobalAndLocalIndexes() {
        assertDoesNotThrow(() -> DynamoDbTableDefinition.validate(simple().build()));
        var request = simple().keySchema(key("id", KeyType.HASH), key("created", KeyType.RANGE))
            .attributeDefinitions(attribute("id"), attribute("created"), attribute("category"), attribute("price"))
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("by-category")
                .keySchema(key("category", KeyType.HASH)).projection(p -> p.projectionType(ProjectionType.ALL))
                .provisionedThroughput(t -> t.readCapacityUnits(2L).writeCapacityUnits(2L)).build())
            .localSecondaryIndexes(LocalSecondaryIndex.builder().indexName("by-price")
                .keySchema(key("id", KeyType.HASH), key("price", KeyType.RANGE))
                .projection(p -> p.projectionType(ProjectionType.INCLUDE).nonKeyAttributes("description")).build())
            .streamSpecification(s -> s.streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)).build();
        assertDoesNotThrow(() -> DynamoDbTableDefinition.validate(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "bad/name", "arn:aws:dynamodb:us-east-1:000000000000:table/test", "white space"})
    void rejectsInvalidTableNames(String name) {
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validateName(name));
    }

    @Test
    void requiresOneHashKeyAndMatchingScalarDefinitions() {
        for (var request : List.of(
                simple().keySchema(List.of()).build(),
                simple().keySchema(key("id", KeyType.RANGE)).build(),
                simple().keySchema(key("id", KeyType.HASH), key("id", KeyType.RANGE)).build(),
                simple().keySchema(key("id", KeyType.HASH), key("other", KeyType.HASH)).build(),
                simple().attributeDefinitions(attribute("other")).build(),
                simple().attributeDefinitions(attribute("id"), attribute("extra")).build(),
                simple().attributeDefinitions(attribute("id"), attribute("id")).build(),
                simple().attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType("BOOL").build()).build())) {
            assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(request));
        }
    }

    @Test
    void countsUnicodeAttributeNamesAsCharacters() {
        String name = "界".repeat(255);
        assertDoesNotThrow(() -> DynamoDbTableDefinition.validate(simple().keySchema(key(name, KeyType.HASH))
            .attributeDefinitions(attribute(name)).build()));
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple().keySchema(key(name + "界", KeyType.HASH))
            .attributeDefinitions(attribute(name + "界")).build()));
    }

    @Test
    void validatesCapacityProjectionAndStreamOptions() {
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple()
            .provisionedThroughput(t -> t.readCapacityUnits(0L).writeCapacityUnits(1L)).build()));
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple()
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("missing-options").keySchema(key("id", KeyType.HASH)).build()).build()));
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple()
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("bad-projection").keySchema(key("id", KeyType.HASH))
                .provisionedThroughput(t -> t.readCapacityUnits(1L).writeCapacityUnits(1L))
                .projection(p -> p.projectionType(ProjectionType.INCLUDE)).build()).build()));
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple()
            .streamSpecification(s -> s.streamEnabled(true)).build()));
    }

    @Test
    void requiresLocalIndexesToShareTheCompositeTablePartitionKey() {
        var index = LocalSecondaryIndex.builder().indexName("by-sort")
            .keySchema(key("id", KeyType.HASH), key("sort", KeyType.RANGE)).projection(p -> p.projectionType(ProjectionType.ALL)).build();
        assertThrows(GradleException.class, () -> DynamoDbTableDefinition.validate(simple()
            .attributeDefinitions(attribute("id"), attribute("sort")).localSecondaryIndexes(index).build()));
    }

    private CreateTableRequest.Builder simple() {
        return CreateTableRequest.builder().tableName("catalog.products").keySchema(key("id", KeyType.HASH))
            .attributeDefinitions(attribute("id")).provisionedThroughput(t -> t.readCapacityUnits(100L).writeCapacityUnits(100L))
            .sseSpecification(s -> s.enabled(true));
    }

    private KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }
}
