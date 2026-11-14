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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsReportCollectorTest {
    @Test void followsGroupAndStreamPagesWithoutReadingLogEventsOrExportingUploadTokens() {
        var client = SdkStub.client(CloudWatchLogsClient.class, (p, m, a) -> switch (m.getName()) {
            case "describeLogGroups" -> {
                String token = ((DescribeLogGroupsRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("groups"));
                yield DescribeLogGroupsResponse.builder().logGroups(LogGroup.builder().logGroupName(token == null ? "/one" : "/two")
                    .arn("arn:logs").retentionInDays(7).storedBytes(42L).kmsKeyId("key").creationTime(1L).build())
                    .nextToken(token == null ? "groups" : null).build();
            }
            case "describeLogStreams" -> {
                var request = (DescribeLogStreamsRequest) a[0];
                assertTrue(List.of("/one", "/two").contains(request.logGroupName()));
                assertTrue(request.nextToken() == null || request.nextToken().equals("streams"));
                yield DescribeLogStreamsResponse.builder().logStreams(LogStream.builder()
                    .logStreamName(request.nextToken() == null ? "a" : "b").creationTime(2L).lastEventTimestamp(3L)
                    .uploadSequenceToken("private-upload-token").build()).nextToken(request.nextToken() == null ? "streams" : null).build();
            }
            default -> throw new AssertionError("Unexpected or log-content call: " + m.getName());
        });
        var section = new ReportSection("CloudWatch Logs");
        new CloudWatchLogsReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        var group = section.result().resources().get(0).configuration();
        assertEquals(7, group.get("retentionInDays"));
        assertEquals(42L, group.get("storedBytes"));
        assertEquals(2, ((List<?>) group.get("streams")).size());
        assertFalse(section.result().toString().contains("private-upload-token"));
    }

    @Test void streamFailuresRetainGroupsAndContinueToTheNextGroup() {
        var client = SdkStub.client(CloudWatchLogsClient.class, (p, m, a) -> switch (m.getName()) {
            case "describeLogGroups" -> DescribeLogGroupsResponse.builder().logGroups(LogGroup.builder().logGroupName("/bad").build(),
                LogGroup.builder().logGroupName("/good").build()).build();
            case "describeLogStreams" -> {
                if (((DescribeLogStreamsRequest) a[0]).logGroupName().equals("/bad")) throw ResourceNotFoundException.builder().build();
                yield DescribeLogStreamsResponse.builder().logStreams(LogStream.builder().logStreamName("retained").build()).build();
            }
            default -> throw new AssertionError(m.getName());
        });
        var section = new ReportSection("CloudWatch Logs");
        new CloudWatchLogsReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(2, section.result().resources().size());
        assertTrue(section.result().resources().get(1).configuration().toString().contains("retained"));
        assertEquals("/bad", section.result().issues().get(0).resource());
    }
}
