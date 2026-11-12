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
