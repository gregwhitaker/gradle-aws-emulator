package io.github.gregwhitaker.awsemulator.aws.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Resolves all targets before publishing, without retrying an entire partially sent input. */
final class SqsMessagePublisher {
    long publish(SqsClient client, List<String> names, List<Path> files, String group) {
        var urls = new SqsQueueOperations().urls(client, names);
        long[] sent = {0};
        new SqsMessageReader().read(files, body -> {
            for (int i = 0; i < names.size(); i++) {
                var request = SendMessageRequest.builder().queueUrl(urls.get(i)).messageBody(body);
                if (group != null) request.messageGroupId(group);
                if (names.get(i).endsWith(".fifo")) request.messageDeduplicationId(UUID.randomUUID().toString());
                client.sendMessage(request.build());
                sent[0]++;
            }
        });
        return sent[0];
    }
}
