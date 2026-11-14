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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.retry.backoff.FixedDelayBackoffStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationExampleTest {
    @Test void stackResourcesReallyServeTheirServiceApis() throws Exception {
        var outputs = new ObjectMapper().readTree(Path.of(System.getProperty("cloudformation.outputs")).toFile());
        String bucket = outputs.path("BucketName").asText();
        String queue = outputs.path("QueueUrl").asText();
        String table = outputs.path("TableName").asText();
        try (var s3 = S3Client.builder().forcePathStyle(true).build();
             var sqs = SqsClient.create(); var dynamo = DynamoDbClient.create()) {
            s3.putObject(r -> r.bucket(bucket).key("probe.txt"), RequestBody.fromString("CloudFormation created this bucket"));
            try {
                assertEquals("CloudFormation created this bucket",
                    s3.getObjectAsBytes(r -> r.bucket(bucket).key("probe.txt")).asUtf8String());
            } finally { s3.deleteObject(r -> r.bucket(bucket).key("probe.txt")); }
            sqs.sendMessage(r -> r.queueUrl(queue).messageBody("CloudFormation created this queue"));
            assertEquals("CloudFormation created this queue", sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(2))
                .messages().get(0).body());
            // Emulator CloudFormation can finish before DynamoDB's table activation.
            try (var waiter = DynamoDbWaiter.builder().client(dynamo).overrideConfiguration(config -> config
                    .waitTimeout(Duration.ofSeconds(30)).maxAttempts(150)
                    .backoffStrategy(FixedDelayBackoffStrategy.create(Duration.ofMillis(200)))).build()) {
                waiter.waitUntilTableExists(r -> r.tableName(table));
            }
            var key = Map.of("id", AttributeValue.fromS("probe"));
            dynamo.putItem(r -> r.tableName(table).item(Map.of("id", AttributeValue.fromS("probe"), "value", AttributeValue.fromS("local"))));
            assertEquals("local", dynamo.getItem(r -> r.tableName(table).key(key).consistentRead(true)).item().get("value").s());
        }
        System.out.println("CloudFormation-created S3, SQS, and DynamoDB resources verified");
        assertFalse(Boolean.getBoolean("example.fail"), "Intentional consumer failure");
    }
}
