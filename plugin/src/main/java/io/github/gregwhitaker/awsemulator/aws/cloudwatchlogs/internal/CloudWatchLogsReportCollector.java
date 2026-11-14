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

package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads log group and stream metadata without fetching or filtering log events. */
public final class CloudWatchLogsReportCollector {
    public void collect(CloudWatchLogsClient client, ReportSection section) {
        section.read("DescribeLogGroups", null, () -> pages(token -> {
            var page = client.describeLogGroups(DescribeLogGroupsRequest.builder().limit(50).nextToken(token).build());
            for (var group : page.logGroups()) {
                String name = group.logGroupName();
                var details = section.resource("AWS::Logs::LogGroup", name);
                details.putAll(fields("name", name, "arn", group.arn(), "createdAtEpochMillis", group.creationTime(),
                    "retentionInDays", group.retentionInDays(), "storedBytes", group.storedBytes(),
                    "kmsKeyId", group.kmsKeyId(), "logGroupClass", group.logGroupClassAsString()));
                var streams = new ArrayList<Map<String, Object>>();
                details.put("streams", streams);
                section.read("DescribeLogStreams", name, () -> pages(next -> {
                    var response = client.describeLogStreams(DescribeLogStreamsRequest.builder()
                        .logGroupName(name).limit(50).nextToken(next).build());
                    for (var stream : response.logStreams()) streams.add(fields("name", stream.logStreamName(), "arn", stream.arn(),
                        "createdAtEpochMillis", stream.creationTime(), "firstEventTimestamp", stream.firstEventTimestamp(),
                        "lastEventTimestamp", stream.lastEventTimestamp(), "lastIngestionTime", stream.lastIngestionTime(),
                        "storedBytes", stream.storedBytes()));
                    return response.nextToken();
                }));
                streams.sort(Comparator.comparing(Object::toString));
            }
            return page.nextToken();
        }));
    }
}
