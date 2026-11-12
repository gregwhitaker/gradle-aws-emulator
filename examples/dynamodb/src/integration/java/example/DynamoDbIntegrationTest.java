package example;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbIntegrationTest {
    @Test
    void readsAnItemWrittenByTheTableInitializer() {
        try (var client = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var item = client.getItem(request -> request.tableName("example-products")
                .key(Map.of("id", AttributeValue.builder().s("product-1").build())).consistentRead(true)).item();
            assertEquals("Example widget", item.get("name").s());
        }
    }

    @Test
    void queriesTheProvisionedSecondaryIndex() {
        try (var client = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var items = client.query(request -> request.tableName("example-products").indexName("by-category")
                .keyConditionExpression("category = :category")
                .expressionAttributeValues(Map.of(":category", AttributeValue.builder().s("widgets").build()))).items();
            assertEquals(1, items.size());
            assertEquals("product-1", items.get(0).get("id").s());
        }
    }
}
