package example;

import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.util.UUID;

/** Micronaut AWS creates and closes the injected SQS client using the normal SDK providers. */
@Singleton
public class Messages {
    private final SqsClient sqs;

    public Messages(SqsClient sqs) { this.sqs = sqs; }

    public String roundTrip() {
        String url = sqs.getQueueUrl(request -> request.queueName("micronaut-example-events")).queueUrl();
        String body = "Hello from Micronaut: " + UUID.randomUUID();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(body));
        var messages = sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(5)).messages();
        if (messages.isEmpty()) throw new IllegalStateException("Expected an SQS message");
        var message = messages.get(0);
        sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
        if (!body.equals(message.body())) throw new IllegalStateException("Unexpected SQS message: " + message.body());
        return message.body();
    }
}
