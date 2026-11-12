package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class EventBridgeOperationsTest {
    @Test void partialPublishingFailsWithoutReplayingSuccessfulEntries() {
        AtomicInteger writes = new AtomicInteger();
        var sdk = client(EventBridgeClient.class, (p, m, a) -> switch (m.getName()) {
            case "describeEventBus" -> DescribeEventBusResponse.builder().build();
            case "putEvents" -> { writes.incrementAndGet(); yield PutEventsResponse.builder().failedEntryCount(1).entries(
                PutEventsResultEntry.builder().eventId("ok").build(), PutEventsResultEntry.builder().errorCode("InternalFailure").build()).build(); }
            default -> throw new AssertionError(m);
        });
        var event = PutEventsRequestEntry.builder().source("test").detailType("created").detail("{}").build();
        var error = assertThrows(GradleException.class, () -> new EventBridgeOperations().publish(sdk, "bus", List.of(event, event)));
        assertTrue(error.getMessage().contains("[2]")); assertEquals(1, writes.get());
    }
    @Test void conflictingTargetDoesNotWriteWithoutOverwrite() {
        var sdk = client(EventBridgeClient.class, (p, m, a) -> {
            assertEquals("listTargetsByRule", m.getName());
            return ListTargetsByRuleResponse.builder().targets(Target.builder().id("queue").arn("old").build()).build();
        });
        assertThrows(GradleException.class, () -> new EventBridgeOperations().putTargets(sdk, "bus", "rule", Map.of("queue", "new"), false));
    }
    @Test void queuePolicyPreservesOtherStatementsAndDoesNotDuplicateItsGrant() throws Exception {
        String existing = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Sid\":\"Existing\",\"Effect\":\"Deny\"}}";
        String first = PutEventBridgeSqsTargetTask.policy(existing, "arn:rule", "arn:queue");
        String second = PutEventBridgeSqsTargetTask.policy(first, "arn:rule", "arn:queue");
        assertEquals(first, second);
        var statements = new ObjectMapper().readTree(second).get("Statement");
        assertEquals(2, statements.size()); assertEquals("Existing", statements.get(0).get("Sid").asText());
    }
}
