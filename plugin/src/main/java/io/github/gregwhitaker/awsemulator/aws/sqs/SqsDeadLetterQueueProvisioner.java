package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates or reuses the DLQ and connects it using its service-reported ARN. */
final class SqsDeadLetterQueueProvisioner {
    static String defaultName(String name) {
        return name.endsWith(".fifo") ? name.substring(0, name.length() - 5) + "-dlq.fifo" : name + "-dlq";
    }

    record Settings(String name, Map<String, String> attributes, String dlqName,
                    Map<String, String> dlqAttributes, int maxReceiveCount) {
        Settings {
            attributes = Map.copyOf(attributes);
            var dlq = new LinkedHashMap<>(dlqAttributes);
            if (name.endsWith(".fifo")) dlq.putIfAbsent("FifoQueue", "true");
            dlqAttributes = Map.copyOf(dlq);
            SqsQueueProvisioner.validate(name, attributes);
            SqsQueueProvisioner.validate(dlqName, dlqAttributes);
            if (name.equals(dlqName)) throw new GradleException("A queue cannot be its own deadletter queue.");
            if (name.endsWith(".fifo") != dlqName.endsWith(".fifo")) throw new GradleException("Source and deadletter queues must have the same queue type.");
            if (maxReceiveCount < 1 || maxReceiveCount > 1000) throw new GradleException("maxReceiveCount must be between 1 and 1000.");
            if (attributes.containsKey("RedrivePolicy")) {
                throw new GradleException("Configure deadletterQueueName and maxReceiveCount instead of setting RedrivePolicy directly.");
            }
        }
    }

    void provision(SqsClient client, Settings settings) {
        String dlqUrl = new SqsQueueProvisioner().provision(client, settings.dlqName(), settings.dlqAttributes());
        String arn = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl)
            .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
        if (arn == null || !arn.matches("arn:[a-z0-9-]+:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(\\.fifo)?")) {
            throw new GradleException("SQS did not return a valid ARN for the deadletter queue.");
        }
        var attributes = new LinkedHashMap<>(settings.attributes());
        attributes.put("RedrivePolicy", "{\"deadLetterTargetArn\":\"" + arn
            + "\",\"maxReceiveCount\":\"" + settings.maxReceiveCount() + "\"}");
        String url;
        try {
            url = client.getQueueUrl(GetQueueUrlRequest.builder().queueName(settings.name()).build()).queueUrl();
        } catch (QueueDoesNotExistException missing) {
            try {
                client.createQueue(CreateQueueRequest.builder().queueName(settings.name()).attributesWithStrings(attributes).build());
                return;
            } catch (QueueNameExistsException raced) {
                url = client.getQueueUrl(GetQueueUrlRequest.builder().queueName(settings.name()).build()).queueUrl();
            }
        }
        // This task explicitly attaches/updates a DLQ on an existing source queue, preserving its messages.
        // FIFO is a creation-only attribute; the queue's suffix already fixes its type.
        attributes.remove("FifoQueue");
        client.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(url).attributesWithStrings(attributes).build());
    }
}
