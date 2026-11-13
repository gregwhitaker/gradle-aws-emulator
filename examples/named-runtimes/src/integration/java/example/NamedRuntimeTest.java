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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NamedRuntimeTest {
    @Test
    void receivesOnlyItsSuitesResourcesAndConnection() throws Exception {
        String suite = System.getProperty("suite");
        Path output = Path.of(System.getProperty("connection.output"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, System.getProperty("aws.endpointUrl") + "\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE") + "\n");
        assertEquals(System.getProperty("expected.region"), System.getenv("AWS_REGION"));
        try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String queue = sqs.getQueueUrl(r -> r.queueName("named-runtime-events")).queueUrl();
            if (!suite.equals("remote")) {
                assertEquals("0", sqs.getQueueAttributes(r -> r.queueUrl(queue)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).attributes()
                    .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES), "Managed suites must start fresh");
            }
            sqs.sendMessage(r -> r.queueUrl(queue).messageBody(suite));
            var message = sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(5)).messages().get(0);
            assertEquals(suite, message.body());
            sqs.deleteMessage(r -> r.queueUrl(queue).receiptHandle(message.receiptHandle()));
        }
        assertNotEquals(suite, System.getProperty("fail.suite"), "Intentional named runtime failure");
    }
}
