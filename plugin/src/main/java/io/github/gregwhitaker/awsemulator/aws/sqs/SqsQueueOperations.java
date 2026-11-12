package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Shared queue lookup, listing, and purge operations. */
final class SqsQueueOperations {
    List<Queue> list(SqsClient client, String prefix) {
        List<Queue> queues = new ArrayList<>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listQueues(ListQueuesRequest.builder().queueNamePrefix(prefix).maxResults(1000).nextToken(token).build());
            for (String url : page.queueUrls()) {
                var attributes = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build()).attributesAsStrings();
                queues.add(new Queue(url.substring(url.lastIndexOf('/') + 1), url,
                    attributes.getOrDefault("ApproximateNumberOfMessages", "unknown")));
            }
            token = page.nextToken();
            if (token != null && !token.isEmpty() && !tokens.add(token)) {
                throw new GradleException("SQS listing returned a repeated continuation token.");
            }
        } while (token != null && !token.isEmpty());
        return queues;
    }

    List<String> urls(SqsClient client, List<String> names) {
        return names.stream().map(name -> client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl()).toList();
    }

    void purge(SqsClient client, List<String> names) {
        // Resolve every name before the first mutation so a missing queue cannot partially purge the selection.
        for (String url : urls(client, names)) client.purgeQueue(PurgeQueueRequest.builder().queueUrl(url).build());
    }

    record Queue(String name, String url, String messages) {}
}
