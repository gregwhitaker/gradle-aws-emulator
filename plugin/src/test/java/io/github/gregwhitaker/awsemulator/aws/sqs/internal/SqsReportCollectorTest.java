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

package io.github.gregwhitaker.awsemulator.aws.sqs.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqsReportCollectorTest {
    @Test void followsPagesAndIncludesConfigurationWithoutReceivingMessages() {
        var client = SdkStub.client(SqsClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listQueues" -> {
                var request = (ListQueuesRequest) args[0];
                assertEquals(1000, request.maxResults());
                assertTrue(request.nextToken() == null || request.nextToken().equals("next"));
                yield ListQueuesResponse.builder().queueUrls("https://localhost/" + (request.nextToken() == null ? "one" : "two"))
                    .nextToken(request.nextToken() == null ? "next" : null).build();
            }
            case "getQueueAttributes" -> GetQueueAttributesResponse.builder().attributesWithStrings(Map.of(
                "VisibilityTimeout", "45", "RedrivePolicy", "{dlq}", "ApproximateNumberOfMessages", "3", "UnexpectedSecret", "omit")).build();
            default -> throw new AssertionError("Unexpected or mutating call: " + method.getName());
        });
        var section = new ReportSection("SQS");
        new SqsReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        assertEquals(Map.of("VisibilityTimeout", "45", "RedrivePolicy", "{dlq}", "ApproximateNumberOfMessages", "3"),
            section.result().resources().get(0).configuration().get("attributes"));
    }
}
