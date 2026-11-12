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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SqsMessagePublisherTest {
    @TempDir Path directory;

    @Test
    void publishesEveryLineToEveryQueueAndDoesNotDeduplicateIdenticalFifoMessages() throws Exception {
        Path messages = Files.writeString(directory.resolve("messages.txt"), "same\nsame\n");
        var sent = new ArrayList<SendMessageRequest>();
        var resolved = new ArrayList<String>();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> {
                String name = ((GetQueueUrlRequest) args[0]).queueName();
                resolved.add(name);
                yield GetQueueUrlResponse.builder().queueUrl("https://local/" + name).build();
            }
            case "sendMessage" -> {
                assertEquals(2, resolved.size());
                sent.add((SendMessageRequest) args[0]);
                yield SendMessageResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertEquals(4, new SqsMessagePublisher().publish(client, List.of("standard", "ordered.fifo"), List.of(messages), "fixture"));
        assertTrue(sent.stream().allMatch(request -> request.messageBody().equals("same")));
        assertNull(sent.get(0).messageDeduplicationId());
        assertEquals("fixture", sent.get(1).messageGroupId());
        assertNotNull(sent.get(1).messageDeduplicationId());
        assertNotEquals(sent.get(1).messageDeduplicationId(), sent.get(3).messageDeduplicationId());
    }

    @Test
    void failsImmediatelyOnSdkErrorsInsteadOfClaimingSuccessOrReplayingTheInput() throws Exception {
        Path messages = Files.writeString(directory.resolve("messages.txt"), "one\ntwo\nthree");
        var sent = new ArrayList<String>();
        var failure = SqsException.builder().statusCode(500).build();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl("https://local/events").build();
            case "sendMessage" -> {
                String body = ((SendMessageRequest) args[0]).messageBody();
                sent.add(body);
                if (body.equals("two")) throw failure;
                yield SendMessageResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        assertSame(failure, assertThrows(SqsException.class,
            () -> new SqsMessagePublisher().publish(client, List.of("events"), List.of(messages), null)));
        assertEquals(List.of("one", "two"), sent);
    }
}
