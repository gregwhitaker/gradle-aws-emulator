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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SqsQueueProvisionerTest {
    private static final String URL = "https://127.0.0.1:9297/000000000000/events";

    @Test
    void createsAMissingFifoQueueWithTheRequestedAttributes() {
        var attributes = Map.of("FifoQueue", "true", "ContentBasedDeduplication", "true");
        AtomicInteger creates = new AtomicInteger();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> {
            if (method.getName().equals("getQueueUrl")) throw QueueDoesNotExistException.builder().build();
            assertEquals("createQueue", method.getName());
            var request = (CreateQueueRequest) args[0];
            assertEquals("events.fifo", request.queueName());
            assertEquals(attributes, request.attributesAsStrings());
            creates.incrementAndGet();
            return CreateQueueResponse.builder().queueUrl(URL + ".fifo").build();
        });
        assertEquals(URL + ".fifo", new SqsQueueProvisioner().provision(client, "events.fifo", attributes));
        assertEquals(1, creates.get());
    }

    @Test
    void reusesAMatchingQueueWithoutCreatingOrChangingIt() {
        var client = existing(Map.of("VisibilityTimeout", "45"));
        assertEquals(URL, new SqsQueueProvisioner().provision(client, "events", Map.of("VisibilityTimeout", "45", "FifoQueue", "false")));
    }

    @Test
    void failsOnAttributeDriftWithoutChangingTheQueue() {
        var failure = assertThrows(GradleException.class, () -> new SqsQueueProvisioner().provision(
            existing(Map.of("VisibilityTimeout", "30")), "events", Map.of("VisibilityTimeout", "45")));
        assertTrue(failure.getMessage().contains("different VisibilityTimeout"));
    }

    @Test
    void propagatesAuthorizationErrorsInsteadOfTryingToCreate() {
        var failure = SqsException.builder().statusCode(403).build();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> {
            assertEquals("getQueueUrl", method.getName());
            throw failure;
        });
        assertSame(failure, assertThrows(SqsException.class, () -> new SqsQueueProvisioner().provision(client, "events", Map.of())));
    }

    @Test
    void rechecksAttributesAfterAConcurrentCreate() {
        AtomicInteger urls = new AtomicInteger();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> {
                if (urls.incrementAndGet() == 1) throw QueueDoesNotExistException.builder().build();
                yield GetQueueUrlResponse.builder().queueUrl(URL).build();
            }
            case "createQueue" -> throw QueueNameExistsException.builder().build();
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder().attributesWithStrings(Map.of("VisibilityTimeout", "30")).build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        assertThrows(GradleException.class, () -> new SqsQueueProvisioner().provision(client, "events", Map.of("VisibilityTimeout", "45")));
        assertEquals(2, urls.get());
    }

    @Test
    void requiresConsistentFifoSettingsAndValidNames() {
        assertThrows(GradleException.class, () -> SqsQueueProvisioner.validate("events.fifo", Map.of()));
        assertThrows(GradleException.class, () -> SqsQueueProvisioner.validate("events", Map.of("FifoQueue", "true")));
        assertThrows(GradleException.class, () -> SqsQueueProvisioner.validate("bad/name", Map.of()));
        assertThrows(GradleException.class, () -> SqsQueueProvisioner.validate("a".repeat(81), Map.of()));
        assertDoesNotThrow(() -> SqsQueueProvisioner.validate("events.fifo", Map.of("FifoQueue", "true")));
    }

    private SqsClient existing(Map<String, String> attributes) {
        return SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl(URL).build();
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder().attributesWithStrings(attributes).build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
    }
}
