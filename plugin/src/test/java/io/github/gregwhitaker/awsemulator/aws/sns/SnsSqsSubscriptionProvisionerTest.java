package io.github.gregwhitaker.awsemulator.aws.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SnsSqsSubscriptionProvisionerTest {
    @Test
    void reusesExistingQueuePreservesItsPolicyAndUpdatesTheSameSubscription() throws Exception {
        String topic = "arn:aws:sns:eu-west-1:123456789012:events";
        String queue = "arn:aws:sqs:eu-west-1:123456789012:consumer";
        String url = "https://localhost:4567/123456789012/consumer";
        String subscription = topic + ":subscription";
        var policy = new AtomicReference<>("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Preserved\",\"Effect\":\"Deny\",\"Action\":\"sqs:DeleteQueue\",\"Resource\":\"*\",\"Principal\":\"*\"}]}");
        var policyWrites = new AtomicInteger();
        var created = new AtomicBoolean();
        var subscribeCalls = new AtomicInteger();
        var raw = new AtomicReference<>("false");
        var sqs = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> {
                assertEquals("consumer", ((GetQueueUrlRequest) args[0]).queueName());
                yield GetQueueUrlResponse.builder().queueUrl(url).build();
            }
            case "getQueueAttributes" -> {
                assertEquals(url, ((GetQueueAttributesRequest) args[0]).queueUrl());
                yield GetQueueAttributesResponse.builder().attributesWithStrings(Map.of("QueueArn", queue, "Policy", policy.get())).build();
            }
            case "setQueueAttributes" -> {
                var request = (SetQueueAttributesRequest) args[0];
                assertEquals(url, request.queueUrl());
                assertEquals(1, request.attributesAsStrings().size());
                policy.set(request.attributesAsStrings().get("Policy"));
                policyWrites.incrementAndGet();
                yield SetQueueAttributesResponse.builder().build();
            }
            default -> throw new AssertionError("Queue must not be recreated or purged: " + method.getName());
        });
        var sns = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().topics(Topic.builder().topicArn(topic).build()).build();
            case "listSubscriptionsByTopic" -> created.get()
                ? ListSubscriptionsByTopicResponse.builder().subscriptions(Subscription.builder().protocol("sqs").endpoint(queue).subscriptionArn(subscription).build()).build()
                : ListSubscriptionsByTopicResponse.builder().build();
            case "subscribe" -> {
                var request = (SubscribeRequest) args[0];
                assertEquals(topic, request.topicArn());
                assertEquals(queue, request.endpoint());
                assertEquals("sqs", request.protocol());
                assertTrue(request.returnSubscriptionArn());
                assertTrue(policy.get().contains(topic));
                created.set(true);
                subscribeCalls.incrementAndGet();
                yield SubscribeResponse.builder().subscriptionArn(subscription).build();
            }
            case "getSubscriptionAttributes" -> GetSubscriptionAttributesResponse.builder().attributes(Map.of("RawMessageDelivery", raw.get())).build();
            case "setSubscriptionAttributes" -> {
                var request = (SetSubscriptionAttributesRequest) args[0];
                assertEquals(subscription, request.subscriptionArn());
                assertEquals("RawMessageDelivery", request.attributeName());
                raw.set(request.attributeValue());
                yield SetSubscriptionAttributesResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        var settings = new SnsSqsSubscriptionProvisioner.Settings("events", Map.of(), "consumer", Map.of(), Map.of("RawMessageDelivery", "true"));
        var provisioner = new SnsSqsSubscriptionProvisioner();
        for (int i = 0; i < 3; i++) assertEquals(subscription, provisioner.provision(sns, sqs, settings));
        assertEquals(1, subscribeCalls.get());
        assertEquals(1, policyWrites.get());
        assertEquals("true", raw.get());
        var statements = new ObjectMapper().readTree(policy.get()).path("Statement");
        assertEquals(2, statements.size());
        assertEquals("Preserved", statements.get(0).path("Sid").asText());
    }

    @Test
    void infersFifoQueueAttributesAndAllowsFifoTopicsToUseStandardQueues() {
        assertEquals("events-subscriber", SnsSqsSubscriptionProvisioner.defaultQueueName("events"));
        assertEquals("events-subscriber.fifo", SnsSqsSubscriptionProvisioner.defaultQueueName("events.fifo"));
        var fifo = new SnsSqsSubscriptionProvisioner.Settings("events.fifo", Map.of("FifoTopic", "true"), "consumer.fifo", Map.of(), Map.of());
        assertEquals("true", fifo.queueAttributes().get("FifoQueue"));
        assertDoesNotThrow(() -> new SnsSqsSubscriptionProvisioner.Settings("events.fifo", Map.of("FifoTopic", "true"), "consumer", Map.of(), Map.of()));
    }

    @Test
    void rejectsIncompatibleQueueSettingsBeforeStartingTheEmulator() {
        assertThrows(GradleException.class, () -> new SnsSqsSubscriptionProvisioner.Settings("events", Map.of(), "consumer.fifo", Map.of(), Map.of()));
        assertThrows(GradleException.class, () -> new SnsSqsSubscriptionProvisioner.Settings("events", Map.of(), "consumer", Map.of("Policy", "{}"), Map.of()));
        assertThrows(GradleException.class, () -> new SnsSqsSubscriptionProvisioner.Settings("events", Map.of(), "consumer", Map.of(), Map.of("RawMessageDelivery", "yes")));
        assertThrows(GradleException.class, () -> new SnsSqsSubscriptionProvisioner.Settings("events", Map.of(), "x".repeat(81), Map.of(), Map.of()));
    }
}
