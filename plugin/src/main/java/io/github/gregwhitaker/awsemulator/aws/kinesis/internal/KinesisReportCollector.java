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

package io.github.gregwhitaker.awsemulator.aws.kinesis.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads stream summaries without opening shard iterators or reading records. */
public final class KinesisReportCollector {
    public void collect(KinesisClient client, ReportSection section) {
        section.read("ListStreams", null, () -> pages(start -> {
            var page = client.listStreams(ListStreamsRequest.builder().exclusiveStartStreamName(start).limit(100).build());
            for (String name : page.streamNames()) {
                var details = section.resource("AWS::Kinesis::Stream", name);
                details.put("name", name);
                section.read("DescribeStreamSummary", name, () -> {
                    var stream = client.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(name).build()).streamDescriptionSummary();
                    details.putAll(fields("arn", stream.streamARN(), "status", stream.streamStatusAsString(),
                        "createdAt", timestamp(stream.streamCreationTimestamp()), "retentionPeriodHours", stream.retentionPeriodHours(),
                        "openShardCount", stream.openShardCount(), "consumerCount", stream.consumerCount(),
                        "encryptionType", stream.encryptionTypeAsString(), "keyId", stream.keyId(),
                        "streamMode", stream.streamModeDetails() == null ? null : stream.streamModeDetails().streamModeAsString()));
                });
            }
            if (!Boolean.TRUE.equals(page.hasMoreStreams())) return null;
            if (page.streamNames().isEmpty()) throw new IllegalStateException("Stream listing made no progress.");
            return page.streamNames().get(page.streamNames().size() - 1);
        }));
    }
}
