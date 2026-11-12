package example.orders;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import static org.junit.jupiter.api.Assertions.*;

class WorkerTest {
    @Test void infrastructureHasProvisionedTheQueue() {
        try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String queue = sqs.getQueueUrl(request -> request.queueName("multi-project-orders")).queueUrl();
            assertTrue(queue.endsWith("/multi-project-orders"));
            assertFalse(Boolean.getBoolean("example.failTest"), "Intentional worker test failure");
        }
    }
}
