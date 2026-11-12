/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
