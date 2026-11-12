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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqsDeadLetterQueueProvisionerTest {
    private static final String ARN = "arn:aws:sqs:eu-west-1:123456789012:events-dlq";

    @Test
    void createsBothQueuesAndUsesTheReportedDlqArn() {
        var created = new ArrayList<CreateQueueRequest>();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> throw QueueDoesNotExistException.builder().build();
            case "createQueue" -> {
                var request = (CreateQueueRequest) args[0];
                created.add(request);
                yield CreateQueueResponse.builder().queueUrl("https://local/" + request.queueName()).build();
            }
            case "getQueueAttributes" -> {
                assertEquals("https://local/events-dlq", ((GetQueueAttributesRequest) args[0]).queueUrl());
                yield GetQueueAttributesResponse.builder().attributesWithStrings(Map.of("QueueArn", ARN)).build();
            }
            default -> throw new AssertionError(method.getName());
        });
        new SqsDeadLetterQueueProvisioner().provision(client, settings(5));
        assertEquals(2, created.size());
        assertEquals("events-dlq", created.get(0).queueName());
        assertEquals("events", created.get(1).queueName());
        assertEquals("45", created.get(1).attributesAsStrings().get("VisibilityTimeout"));
        assertEquals("{\"deadLetterTargetArn\":\"" + ARN + "\",\"maxReceiveCount\":\"5\"}",
            created.get(1).attributesAsStrings().get("RedrivePolicy"));
    }

    @Test
    void updatesAnExistingSourceQueueWithoutRecreatingEitherQueue() {
        var updates = new ArrayList<SetQueueAttributesRequest>();
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl("https://local/" + ((GetQueueUrlRequest) args[0]).queueName()).build();
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder().attributesWithStrings(Map.of("QueueArn", ARN)).build();
            case "setQueueAttributes" -> { updates.add((SetQueueAttributesRequest) args[0]); yield SetQueueAttributesResponse.builder().build(); }
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        new SqsDeadLetterQueueProvisioner().provision(client, settings(3));
        assertEquals(1, updates.size());
        assertEquals("https://local/events", updates.get(0).queueUrl());
        assertEquals("45", updates.get(0).attributesAsStrings().get("VisibilityTimeout"));
        assertTrue(updates.get(0).attributesAsStrings().get("RedrivePolicy").contains(ARN));
    }

    @Test
    void refusesToAttachAnUnidentifiedDlq() {
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl("https://local/events-dlq").build();
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder().build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        assertThrows(GradleException.class, () -> new SqsDeadLetterQueueProvisioner().provision(client, settings(1)));
    }

    @Test
    void doesNotSendTheCreationOnlyFifoAttributeWhenUpdatingAnExistingQueue() {
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getQueueUrl" -> GetQueueUrlResponse.builder().queueUrl("https://local/" + ((GetQueueUrlRequest) args[0]).queueName()).build();
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder()
                .attributesWithStrings(Map.of("FifoQueue", "true", "QueueArn", ARN + ".fifo")).build();
            case "setQueueAttributes" -> {
                var request = (SetQueueAttributesRequest) args[0];
                assertEquals("https://local/events.fifo", request.queueUrl());
                assertFalse(request.attributesAsStrings().containsKey("FifoQueue"));
                assertTrue(request.attributesAsStrings().containsKey("RedrivePolicy"));
                yield SetQueueAttributesResponse.builder().build();
            }
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        new SqsDeadLetterQueueProvisioner().provision(client, new SqsDeadLetterQueueProvisioner.Settings(
            "events.fifo", Map.of("FifoQueue", "true"), "events-dlq.fifo", Map.of(), 1));
    }

    @Test
    void derivesFifoDlqNameAndType() {
        assertEquals("events-dlq", SqsDeadLetterQueueProvisioner.defaultName("events"));
        assertEquals("events-dlq.fifo", SqsDeadLetterQueueProvisioner.defaultName("events.fifo"));
        var settings = new SqsDeadLetterQueueProvisioner.Settings("events.fifo", Map.of("FifoQueue", "true"),
            "events-dlq.fifo", Map.of(), 1);
        assertEquals("true", settings.dlqAttributes().get("FifoQueue"));
    }

    @Test
    void validatesDlqConfigurationBeforeMakingRequests() {
        assertThrows(GradleException.class, () -> new SqsDeadLetterQueueProvisioner.Settings("events", Map.of(), "events", Map.of(), 1));
        assertThrows(GradleException.class, () -> new SqsDeadLetterQueueProvisioner.Settings("events", Map.of(), "dead.fifo", Map.of("FifoQueue", "true"), 1));
        assertThrows(GradleException.class, () -> new SqsDeadLetterQueueProvisioner.Settings("events.fifo", Map.of("FifoQueue", "true"), "dead.fifo", Map.of("FifoQueue", "false"), 1));
        assertThrows(GradleException.class, () -> new SqsDeadLetterQueueProvisioner.Settings("events", Map.of("RedrivePolicy", "{}"), "dead", Map.of(), 1));
        assertThrows(GradleException.class, () -> settings(0));
        assertThrows(GradleException.class, () -> settings(1001));
    }

    private SqsDeadLetterQueueProvisioner.Settings settings(int maxReceiveCount) {
        return new SqsDeadLetterQueueProvisioner.Settings("events", Map.of("VisibilityTimeout", "45"), "events-dlq", Map.of(), maxReceiveCount);
    }
}
