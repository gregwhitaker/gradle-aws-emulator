package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnsTopicOperationsTest {
    private static final String ARN = "arn:aws:sns:eu-west-1:123456789012:events";
    private final SnsTopicOperations operations = new SnsTopicOperations();

    @Test
    void createsMissingTopicAndUsesTheReturnedArn() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().build();
            case "createTopic" -> {
                var request = (CreateTopicRequest) args[0];
                assertEquals("events", request.name());
                assertEquals(Map.of("DisplayName", "Events"), request.attributes());
                yield CreateTopicResponse.builder().topicArn(ARN).build();
            }
            case "getTopicAttributes" -> {
                assertEquals(ARN, ((GetTopicAttributesRequest) args[0]).topicArn());
                yield GetTopicAttributesResponse.builder().attributes(Map.of("DisplayName", "Events")).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertEquals(ARN, operations.provision(client, "events", Map.of("DisplayName", "Events")));
    }

    @Test
    void reusesMatchingTopicsAndRejectsAttributeConflictsWithoutMutatingThem() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().topics(Topic.builder().topicArn(ARN).build()).build();
            case "getTopicAttributes" -> GetTopicAttributesResponse.builder().attributes(Map.of("DisplayName", "Existing")).build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        assertEquals(ARN, operations.provision(client, "events", Map.of()));
        assertEquals(ARN, operations.provision(client, "events", Map.of("DisplayName", "Existing", "FifoTopic", "false")));
        assertTrue(assertThrows(GradleException.class,
            () -> operations.provision(client, "events", Map.of("DisplayName", "Changed"))).getMessage().contains("different DisplayName"));
    }

    @Test
    void resolvesExactNamesAcrossPagesAndListsAllSubscriptions() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> {
                var request = (ListTopicsRequest) args[0];
                if (request.nextToken() == null) {
                    yield ListTopicsResponse.builder().topics(Topic.builder().topicArn(ARN + "-other").build()).nextToken("page2").build();
                }
                assertEquals("page2", request.nextToken());
                yield ListTopicsResponse.builder().topics(Topic.builder().topicArn(ARN).build()).nextToken("").build();
            }
            case "listSubscriptionsByTopic" -> {
                var request = (ListSubscriptionsByTopicRequest) args[0];
                assertEquals(ARN, request.topicArn());
                int page = calls.incrementAndGet();
                assertEquals(page == 1 ? null : "page2", request.nextToken());
                yield ListSubscriptionsByTopicResponse.builder().subscriptions(Subscription.builder().subscriptionArn("sub" + page).build())
                    .nextToken(page == 1 ? "page2" : null).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertEquals(ARN, operations.require(client, "events"));
        assertEquals(List.of("sub1", "sub2"), operations.subscriptions(client, ARN).stream().map(Subscription::subscriptionArn).toList());
    }

    @Test
    void rejectsRepeatedTokensForBothListings() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().nextToken("repeat").build();
            case "listSubscriptionsByTopic" -> ListSubscriptionsByTopicResponse.builder().nextToken("repeat").build();
            default -> throw new AssertionError(method.getName());
        });
        assertThrows(GradleException.class, () -> operations.list(client));
        assertThrows(GradleException.class, () -> operations.subscriptions(client, ARN));
    }

    @Test
    void absentTopicsAreSafeToDeleteButCannotBeUsedAsPublishTargets() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> {
            assertEquals("listTopics", method.getName());
            return ListTopicsResponse.builder().build();
        });
        operations.delete(client, "events");
        assertTrue(assertThrows(GradleException.class, () -> operations.require(client, "events")).getMessage().contains("does not exist"));
    }

    @Test
    void deletesResolvedArnAndPropagatesUnexpectedServiceErrors() {
        var failure = AuthorizationErrorException.builder().build();
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().topics(Topic.builder().topicArn(ARN).build()).build();
            case "deleteTopic" -> {
                assertEquals(ARN, ((DeleteTopicRequest) args[0]).topicArn());
                throw failure;
            }
            default -> throw new AssertionError(method.getName());
        });
        assertSame(failure, assertThrows(AuthorizationErrorException.class, () -> operations.delete(client, "events")));
    }

    @Test
    void validatesNamesAndFifoAttributesBeforeCalls() {
        for (String name : List.of("", "bad/name", "bad.name", "x".repeat(257))) {
            assertThrows(GradleException.class, () -> SnsTopicOperations.validateName(name));
        }
        assertThrows(GradleException.class, () -> SnsTopicOperations.validate("events.fifo", Map.of()));
        assertThrows(GradleException.class, () -> SnsTopicOperations.validate("events", Map.of("FifoTopic", "true")));
        assertThrows(GradleException.class, () -> SnsTopicOperations.validate("events", Map.of("FifoTopic", "yes")));
        assertDoesNotThrow(() -> SnsTopicOperations.validate("events.fifo", Map.of("FifoTopic", "true")));
    }
}
