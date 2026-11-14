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

package io.github.gregwhitaker.awsemulator.aws.sns.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import static org.junit.jupiter.api.Assertions.*;

class SnsReportCollectorTest {
    @Test void followsTopicAndSubscriptionPagesAndOmitsNonAwsEndpointValues() {
        var client = SdkStub.client(SnsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTopics" -> {
                String token = ((ListTopicsRequest) args[0]).nextToken();
                assertTrue(token == null || token.equals("topics"));
                yield ListTopicsResponse.builder().topics(Topic.builder().topicArn("arn:" + (token == null ? "one" : "two")).build())
                    .nextToken(token == null ? "topics" : null).build();
            }
            case "getTopicAttributes" -> GetTopicAttributesResponse.builder().build();
            case "listSubscriptionsByTopic" -> {
                String token = ((ListSubscriptionsByTopicRequest) args[0]).nextToken();
                assertTrue(token == null || token.equals("subscriptions"));
                yield ListSubscriptionsByTopicResponse.builder().subscriptions(Subscription.builder().subscriptionArn("sub:" + (token == null ? "one" : "two"))
                    .protocol(token == null ? "sqs" : "https").endpoint(token == null ? "arn:aws:sqs:target" : "https://user:secret@host").build())
                    .nextToken(token == null ? "subscriptions" : null).build();
            }
            default -> throw new AssertionError("Unexpected or mutating call: " + method.getName());
        });
        var section = new ReportSection("SNS");
        new SnsReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        var subscriptions = (java.util.List<?>) section.result().resources().get(0).configuration().get("subscriptions");
        assertEquals(2, subscriptions.size());
        assertTrue(subscriptions.toString().contains("arn:aws:sqs:target"));
        assertFalse(subscriptions.toString().contains("secret@host"));
    }
}
