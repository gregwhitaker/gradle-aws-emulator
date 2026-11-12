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
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioTest {
    @Test void applicationCanReadTheSelectedDataset() {
        String scenario = System.getProperty("scenario");
        try (var s3 = S3Client.builder().forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var dynamo = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var objects = s3.listObjectsV2(r -> r.bucket("scenario-assets")).contents();
            var items = dynamo.scan(r -> r.tableName("scenario-products").consistentRead(true)).items();
            if (scenario.equals("empty")) {
                assertTrue(objects.isEmpty());
                assertTrue(items.isEmpty());
                return;
            }
            assertEquals(2, objects.size());
            var metadata = s3.headObject(r -> r.bucket("scenario-assets").key("assets/nested/message.txt"));
            assertEquals("text/plain", metadata.contentType());
            assertEquals("scenario-example", metadata.metadata().get("fixture"));
            assertTrue(s3.getObjectAsBytes(r -> r.bucket("scenario-assets").key("settings/app.json"))
                .asUtf8String().contains("scenario-example"));
            if (scenario.equals("failed-orders")) {
                assertEquals(1, items.size());
                assertEquals("PAYMENT_FAILED", items.get(0).get("status").s());
            } else {
                assertEquals(2, items.size());
                var book = dynamo.getItem(r -> r.tableName("scenario-products").consistentRead(true)
                    .key(Map.of("id", AttributeValue.builder().s("book").build()))).item();
                assertEquals(0, new BigDecimal("12.50").compareTo(new BigDecimal(book.get("price").n())));
                assertArrayEquals(new byte[] {0, 1, -1}, book.get("attachment").b().asByteArray());
                assertEquals(2, book.get("details").m().get("ratings").l().size());
            }
        }
    }
}
