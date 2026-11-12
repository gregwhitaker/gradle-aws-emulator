package example.orders;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** A finite stand-in for the order API's message publishing operation. */
public final class Publisher {
    public static void main(String[] args) {
        try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String queue = sqs.getQueueUrl(request -> request.queueName("multi-project-orders")).queueUrl();
            sqs.sendMessage(request -> request.queueUrl(queue).messageBody("order-1001"));
            System.out.println("Published order-1001 to the shared queue");
        }
    }
}
