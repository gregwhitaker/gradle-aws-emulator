package io.github.gregwhitaker.awsemulator.aws.sns;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes one message without replaying a failed operation outside the SDK's bounded retries. */
final class SnsMessagePublisher {
    static void validate(String name, String message, String subject, String group, String deduplication) {
        SnsTopicOperations.validateName(name);
        if (message == null || message.isEmpty() || !StandardCharsets.UTF_8.newEncoder().canEncode(message)
            || message.getBytes(StandardCharsets.UTF_8).length > 262_144) {
            throw new GradleException("message must be valid UTF-8 and contain 1–262144 bytes.");
        }
        if (subject != null && (subject.isEmpty() || subject.codePointCount(0, subject.length()) >= 100
            || subject.codePoints().anyMatch(Character::isISOControl) || !StandardCharsets.UTF_8.newEncoder().canEncode(subject))) {
            throw new GradleException("subject must contain 1–99 characters without control characters or line breaks.");
        }
        if (name.endsWith(".fifo") && (group == null || group.isBlank())) {
            throw new GradleException("messageGroupId is required when publishing to FIFO topics.");
        }
        identifier(group, "messageGroupId");
        identifier(deduplication, "messageDeduplicationId");
        if (!name.endsWith(".fifo") && deduplication != null) {
            throw new GradleException("messageDeduplicationId is only supported for FIFO topics.");
        }
    }

    String publish(SnsClient client, String name, String message, String subject, String group, String deduplication) {
        validate(name, message, subject, group, deduplication);
        String arn = new SnsTopicOperations().require(client, name);
        var request = PublishRequest.builder().topicArn(arn).message(message).subject(subject).messageGroupId(group);
        if (name.endsWith(".fifo")) request.messageDeduplicationId(deduplication == null ? UUID.randomUUID().toString() : deduplication);
        return client.publish(request.build()).messageId();
    }

    private static void identifier(String value, String label) {
        if (value != null && (value.isEmpty() || value.length() > 128 || value.chars().anyMatch(c -> c < 33 || c > 126))) {
            throw new GradleException(label + " must contain 1–128 alphanumeric or punctuation characters.");
        }
    }
}
