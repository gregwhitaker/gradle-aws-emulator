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

package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SqsQueueOperationsTest {
    @Test
    void listsAllPagesWithPrefixAndMessageCounts() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listQueues" -> {
                var request = (ListQueuesRequest) args[0];
                assertEquals("events-", request.queueNamePrefix());
                assertEquals(1000, request.maxResults());
                if (calls.getAndIncrement() == 0) {
                    assertNull(request.nextToken());
                    yield ListQueuesResponse.builder().queueUrls("https://local/000000000000/events-one").nextToken("next").build();
                }
                assertEquals("next", request.nextToken());
                yield ListQueuesResponse.builder().queueUrls("https://local/000000000000/events-two").build();
            }
            case "getQueueAttributes" -> {
                assertEquals(List.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES), ((GetQueueAttributesRequest) args[0]).attributeNames());
                yield GetQueueAttributesResponse.builder().attributesWithStrings(Map.of("ApproximateNumberOfMessages", "7")).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        var queues = new SqsQueueOperations().list(client, "events-");
        assertEquals(List.of("events-one", "events-two"), queues.stream().map(SqsQueueOperations.Queue::name).toList());
        assertTrue(queues.stream().allMatch(queue -> queue.messages().equals("7")));
    }

    @Test
    void refusesRepeatedListingTokens() {
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> ListQueuesResponse.builder().nextToken("repeat").build());
        assertThrows(GradleException.class, () -> new SqsQueueOperations().list(client, null));
    }

    @Test
    void resolvesAllQueuesBeforePurgingAndDoesNotPurgeIfAnyAreMissing() {
        var calls = new ArrayList<String>();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> {
            assertEquals("getQueueUrl", method.getName());
            String name = ((GetQueueUrlRequest) args[0]).queueName();
            calls.add(name);
            if (name.equals("missing")) throw QueueDoesNotExistException.builder().build();
            return GetQueueUrlResponse.builder().queueUrl("https://local/" + name).build();
        });
        assertThrows(QueueDoesNotExistException.class, () -> new SqsQueueOperations().purge(client, List.of("first", "missing")));
        assertEquals(List.of("first", "missing"), calls);
    }

    @Test
    void propagatesPurgeFailuresWithoutRetryingTheWholeSelection() {
        var calls = new ArrayList<String>();
        var failure = PurgeQueueInProgressException.builder().build();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl("https://local/" + ((GetQueueUrlRequest) args[0]).queueName()).build();
            case "purgeQueue" -> {
                String url = ((PurgeQueueRequest) args[0]).queueUrl();
                calls.add(url);
                if (url.endsWith("second")) throw failure;
                yield PurgeQueueResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertSame(failure, assertThrows(PurgeQueueInProgressException.class,
            () -> new SqsQueueOperations().purge(client, List.of("first", "second", "third"))));
        assertEquals(List.of("https://local/first", "https://local/second"), calls);
    }
}
