package example;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;

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
}
