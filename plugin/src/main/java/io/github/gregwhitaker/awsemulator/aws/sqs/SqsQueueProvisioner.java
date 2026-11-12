package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;

import java.util.Map;
import java.util.Objects;

/** Creates missing queues and verifies explicitly configured attributes on existing queues. */
final class SqsQueueProvisioner {
    static void validate(String name, Map<String, String> attributes) {
        if (name.length() > 80 || !name.matches("[A-Za-z0-9_-]+(\\.fifo)?")) {
            throw new GradleException("queueName must contain 1–80 letters, numbers, underscores or hyphens, with an optional .fifo suffix.");
        }
        String fifo = attributes.getOrDefault("FifoQueue", "false");
        if ((!fifo.equals("true") && !fifo.equals("false")) || name.endsWith(".fifo") != fifo.equals("true")) {
            throw new GradleException("FIFO queues require both a .fifo queueName and the FifoQueue attribute set to 'true'.");
        }
        if (attributes.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new GradleException("SQS attribute names and values must not be blank.");
        }
    }

    String provision(SqsClient client, String name, Map<String, String> attributes) {
        validate(name, attributes);
        String url;
        try {
            url = client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
        } catch (QueueDoesNotExistException missing) {
            try {
                return client.createQueue(CreateQueueRequest.builder().queueName(name).attributesWithStrings(attributes).build()).queueUrl();
            } catch (QueueNameExistsException raced) {
                url = client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
            }
        }
        if (!attributes.isEmpty()) {
            Map<String, String> actual = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNamesWithStrings(attributes.keySet()).build()).attributesAsStrings();
            for (var entry : attributes.entrySet()) {
                String value = entry.getKey().equals("FifoQueue") ? actual.getOrDefault(entry.getKey(), "false") : actual.get(entry.getKey());
                if (!Objects.equals(entry.getValue(), value)) {
                    throw new GradleException("SQS queue '" + name + "' already exists with a different " + entry.getKey()
                        + " attribute. Existing queues are not modified; choose another name or restart the emulator.");
                }
            }
        }
        return url;
    }
}
