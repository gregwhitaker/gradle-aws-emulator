package example.buildsrc.dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import java.util.Map;

/** Seeds the newly created table using the plugin's authenticated emulator client. */
public class ProductTableInitializer {
    private final DynamoDbClient client;

    public ProductTableInitializer(DynamoDbClient client) { this.client = client; }

    public void run() {
        client.putItem(request -> request.tableName("example-products").item(Map.of(
            "id", AttributeValue.builder().s("product-1").build(),
            "name", AttributeValue.builder().s("Example widget").build(),
            "category", AttributeValue.builder().s("widgets").build())));
    }
}
