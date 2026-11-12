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

package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnsMessagePublisherTest {
    @Test
    void publishesToTheResolvedTopicAndPreservesRepeatedFifoMessages() {
        var sent = new ArrayList<PublishRequest>();
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().topics(
                Topic.builder().topicArn("arn:aws:sns:eu-west-1:123456789012:events.fifo").build(),
                Topic.builder().topicArn("arn:aws:sns:eu-west-1:123456789012:events").build()).build();
            case "publish" -> {
                sent.add((PublishRequest) args[0]);
                yield PublishResponse.builder().messageId("id").build();
            }
            default -> throw new AssertionError(method.getName());
        });
        var publisher = new SnsMessagePublisher();
        assertEquals("id", publisher.publish(client, "events", "message", "Subject", null, null));
        publisher.publish(client, "events.fifo", "same", null, "group", null);
        publisher.publish(client, "events.fifo", "same", null, "group", null);
        publisher.publish(client, "events.fifo", "same", null, "group", "explicit-id");
        assertEquals("arn:aws:sns:eu-west-1:123456789012:events", sent.get(0).topicArn());
        assertEquals("Subject", sent.get(0).subject());
        assertNull(sent.get(0).messageDeduplicationId());
        assertEquals("group", sent.get(1).messageGroupId());
        assertNotNull(sent.get(1).messageDeduplicationId());
        assertNotEquals(sent.get(1).messageDeduplicationId(), sent.get(2).messageDeduplicationId());
        assertEquals("explicit-id", sent.get(3).messageDeduplicationId());
    }

    @Test
    void validatesUtf8ByteSizeAndFifoOptionsBeforeAnySdkCalls() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var publisher = new SnsMessagePublisher();
        assertThrows(GradleException.class, () -> publisher.publish(client, "events", "é".repeat(131_073), null, null, null));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events", "\ud800", null, null, null));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events", "", null, null, null));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events.fifo", "body", null, null, null));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events", "body", "bad\nsubject", null, null));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events", "body", null, null, "fifo-only"));
        assertThrows(GradleException.class, () -> publisher.publish(client, "events.fifo", "body", null, "bad group", null));
        assertDoesNotThrow(() -> SnsMessagePublisher.validate("events", "é".repeat(131_072), null, null, null));
    }

    @Test
    void propagatesPublishFailuresWithoutReplayingTheMessage() {
        var failure = SnsException.builder().statusCode(500).build();
        var calls = new AtomicInteger();
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> ListTopicsResponse.builder().topics(Topic.builder().topicArn("arn:aws:sns:us-east-1:123456789012:events").build()).build();
            case "publish" -> { calls.incrementAndGet(); throw failure; }
            default -> throw new AssertionError(method.getName());
        });
        assertSame(failure, assertThrows(SnsException.class, () -> new SnsMessagePublisher().publish(client, "events", "body", null, null, null)));
        assertEquals(1, calls.get());
    }
}
