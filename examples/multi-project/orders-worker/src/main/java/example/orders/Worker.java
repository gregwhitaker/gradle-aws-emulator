package example.orders;

import java.time.Duration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Consumes the order sent by the other module, then exits so the example completes unattended. */
public final class Worker {
    public static void main(String[] args) {
        try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(30))).build()) {
            String queue = sqs.getQueueUrl(request -> request.queueName("multi-project-orders")).queueUrl();
            var messages = sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(10)).messages();
            if (messages.isEmpty()) throw new IllegalStateException("Expected the other module's order");
            var message = messages.get(0);
            if (!message.body().equals("order-1001")) throw new IllegalStateException("Unexpected order: " + message.body());
            sqs.deleteMessage(request -> request.queueUrl(queue).receiptHandle(message.receiptHandle()));
            System.out.println("Processed order-1001 from the other module");
            if (Boolean.getBoolean("example.fail")) throw new IllegalStateException("Intentional worker failure");
        }
    }
}
