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
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorIntegrationTest {
    @Test
    void signsAnAwsRequestAndVerifiesTheEmulatorCertificate() {
        assertNull(System.getenv("AWS_ACCESS_KEY_ID"));
        assertNull(System.getenv("AWS_SECRET_ACCESS_KEY"));
        assertNull(System.getenv("AWS_PROFILE"));
        assertNull(System.getProperty("aws.accessKeyId"));
        assertNull(System.getProperty("aws.secretAccessKey"));
        // Endpoint, credentials, region, and TLS trust come from the plugin.
        try (StsClient sts = StsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            var identity = sts.getCallerIdentity();
            assertEquals("000000000000", identity.account());
            assertEquals("arn:aws:iam::000000000000:user/emulator", identity.arn());
        }
        assertFalse(Boolean.getBoolean("example.fail"), "Intentional failure to demonstrate managed cleanup");
    }
    @Test
    void readsAndWritesTheProvisionedBucket() {
        try (S3Client s3 = S3Client.builder().forcePathStyle(true)
                .overrideConfiguration(config -> config.putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            s3.putObject(request -> request.bucket("example-test-bucket").key("greeting.txt"), RequestBody.fromString("Hello emulator"));
            assertEquals("Hello emulator", s3.getObjectAsBytes(request -> request.bucket("example-test-bucket").key("greeting.txt")).asUtf8String());
        }
    }

    @Test
    void sendsAndReceivesOnProvisionedStandardAndFifoQueues() {
        try (SqsClient sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            for (String name : new String[] {"example-test-queue", "example-events.fifo"}) {
                String url = sqs.getQueueUrl(request -> request.queueName(name)).queueUrl();
                sqs.sendMessage(request -> {
                    request.queueUrl(url).messageBody("Hello queue");
                    if (name.endsWith(".fifo")) request.messageGroupId("example");
                });
                var messages = sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(5)).messages();
                assertEquals(1, messages.size());
                assertEquals("Hello queue", messages.get(0).body());
                sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(messages.get(0).receiptHandle()));
            }
        }
    }

    @Test
    void readsPublishedFixturesAndTheFifoDeadletterPolicy() {
        try (SqsClient sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String url = sqs.getQueueUrl(request -> request.queueName("example-seeded-queue")).queueUrl();
            var bodies = sqs.receiveMessage(request -> request.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5))
                .messages().stream().map(message -> message.body()).collect(Collectors.toSet());
            assertEquals(Set.of("{\"event\":\"order-created\",\"orderId\":\"example-1\"}",
                "{\"event\":\"order-shipped\",\"orderId\":\"example-1\"}"), bodies);
            String fifoUrl = sqs.getQueueUrl(request -> request.queueName("example-events.fifo")).queueUrl();
            String policy = sqs.getQueueAttributes(request -> request.queueUrl(fifoUrl).attributeNamesWithStrings("RedrivePolicy"))
                .attributesAsStrings().get("RedrivePolicy");
            assertTrue(policy.contains("example-events-dlq.fifo"));
        }
    }
}
