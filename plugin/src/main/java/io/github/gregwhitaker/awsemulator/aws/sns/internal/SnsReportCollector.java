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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads topic settings and subscription relationships without publishing or consuming messages. */
public final class SnsReportCollector {
    public void collect(SnsClient client, ReportSection section) {
        section.read("ListTopics", null, () -> pages(token -> {
            var page = client.listTopics(ListTopicsRequest.builder().nextToken(token).build());
            for (var topic : page.topics()) {
                String arn = topic.topicArn();
                var details = section.resource("AWS::SNS::Topic", arn);
                details.put("name", arn.substring(arn.lastIndexOf(':') + 1));
                section.read("GetTopicAttributes", arn, () -> details.put("attributes", attributes(client.getTopicAttributes(
                    GetTopicAttributesRequest.builder().topicArn(arn).build()).attributes(),
                    "DisplayName", "FifoTopic", "ContentBasedDeduplication", "KmsMasterKeyId", "FifoThroughputScope",
                    "SubscriptionsConfirmed", "SubscriptionsPending", "SubscriptionsDeleted")));
                var subscriptions = new ArrayList<Map<String, Object>>();
                details.put("subscriptions", subscriptions);
                section.read("ListSubscriptionsByTopic", arn, () -> pages(next -> {
                    var response = client.listSubscriptionsByTopic(ListSubscriptionsByTopicRequest.builder().topicArn(arn).nextToken(next).build());
                    for (var subscription : response.subscriptions()) subscriptions.add(fields(
                        "arn", subscription.subscriptionArn(), "protocol", subscription.protocol(),
                        "endpoint", "sqs".equals(subscription.protocol()) || "lambda".equals(subscription.protocol())
                            ? subscription.endpoint() : "[omitted]"));
                    return response.nextToken();
                }));
                subscriptions.sort(Comparator.comparing(Object::toString));
            }
            return page.nextToken();
        }));
    }
}
