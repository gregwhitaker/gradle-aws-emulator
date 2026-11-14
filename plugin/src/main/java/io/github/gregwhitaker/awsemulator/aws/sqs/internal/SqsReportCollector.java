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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads queue configuration and approximate counts without receiving messages. */
public final class SqsReportCollector {
    public void collect(SqsClient client, ReportSection section) {
        section.read("ListQueues", null, () -> pages(token -> {
            var page = client.listQueues(ListQueuesRequest.builder().maxResults(1000).nextToken(token).build());
            for (String url : page.queueUrls()) {
                var details = section.resource("AWS::SQS::Queue", url);
                details.put("name", url.substring(url.lastIndexOf('/') + 1));
                section.read("GetQueueAttributes", url, () -> details.put("attributes", attributes(client.getQueueAttributes(
                    GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.ALL).build()).attributesAsStrings(),
                    "QueueArn", "FifoQueue", "ContentBasedDeduplication", "DeduplicationScope", "FifoThroughputLimit",
                    "VisibilityTimeout", "DelaySeconds", "MaximumMessageSize", "MessageRetentionPeriod", "ReceiveMessageWaitTimeSeconds",
                    "RedrivePolicy", "RedriveAllowPolicy", "KmsMasterKeyId", "KmsDataKeyReusePeriodSeconds", "SqsManagedSseEnabled",
                    "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible", "ApproximateNumberOfMessagesDelayed",
                    "CreatedTimestamp", "LastModifiedTimestamp")));
            }
            return page.nextToken();
        }));
    }
}
