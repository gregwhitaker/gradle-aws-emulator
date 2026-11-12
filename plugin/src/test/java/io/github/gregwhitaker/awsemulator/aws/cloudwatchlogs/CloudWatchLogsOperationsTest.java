package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsOperationsTest {
    @Test void unchangedForwardTokenEndsGetLogEvents() {
        AtomicInteger calls = new AtomicInteger();
        var sdk = client(CloudWatchLogsClient.class, (p, m, a) -> calls.getAndIncrement() == 0
            ? GetLogEventsResponse.builder().nextForwardToken("end").events(OutputLogEvent.builder().timestamp(1L).message("ready").build()).build()
            : GetLogEventsResponse.builder().nextForwardToken("end").build());
        var result = new CloudWatchLogsOperations().get(sdk, "group", "stream", null, 10);
        assertEquals(1, result.size()); assertEquals(2, calls.get());
    }
    @Test void filterFollowsEmptyPagesAndRejectsRepeatedTokens() {
        var sdk = client(CloudWatchLogsClient.class, (p, m, a) -> FilterLogEventsResponse.builder().nextToken("loop").build());
        assertThrows(GradleException.class, () -> new CloudWatchLogsOperations().filter(sdk, "group", "", null, 10));
    }
}
