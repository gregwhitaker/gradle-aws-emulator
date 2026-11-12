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
