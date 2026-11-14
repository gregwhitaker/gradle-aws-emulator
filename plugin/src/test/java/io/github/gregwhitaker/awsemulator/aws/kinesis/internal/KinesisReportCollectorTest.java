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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class KinesisReportCollectorTest {
    @Test void followsStreamNamePaginationAndContinuesAfterADescriptionFailure() {
        var client = SdkStub.client(KinesisClient.class, (p, m, a) -> switch (m.getName()) {
            case "listStreams" -> {
                String start = ((ListStreamsRequest) a[0]).exclusiveStartStreamName();
                assertTrue(start == null || start.equals("first"));
                yield ListStreamsResponse.builder().streamNames(start == null ? "first" : "second").hasMoreStreams(start == null).build();
            }
            case "describeStreamSummary" -> {
                String name = ((DescribeStreamSummaryRequest) a[0]).streamName();
                if (name.equals("first")) throw ResourceNotFoundException.builder().message("private-response").build();
                yield DescribeStreamSummaryResponse.builder().streamDescriptionSummary(StreamDescriptionSummary.builder()
                    .streamName(name).streamARN("arn:stream:" + name).streamStatus(StreamStatus.ACTIVE).openShardCount(2)
                    .retentionPeriodHours(24).consumerCount(1).encryptionType(EncryptionType.KMS).keyId("key")
                    .streamModeDetails(StreamModeDetails.builder().streamMode(StreamMode.PROVISIONED).build())
                    .streamCreationTimestamp(Instant.EPOCH).build()).build();
            }
            default -> throw new AssertionError("Unexpected or data-plane call: " + m.getName());
        });
        var section = new ReportSection("Kinesis");
        new KinesisReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(2, section.result().resources().size());
        var metadata = section.result().resources().get(1).configuration();
        assertEquals("PROVISIONED", metadata.get("streamMode"));
        assertEquals(2, metadata.get("openShardCount"));
        assertEquals("KMS", metadata.get("encryptionType"));
        assertEquals("first", section.result().issues().get(0).resource());
        assertFalse(section.result().toString().contains("private-response"));
    }

    @Test void reportsTruncatedEmptyPagesAndRepeatedStreamNamesInsteadOfLooping() {
        for (boolean empty : new boolean[] {true, false}) {
            var client = SdkStub.client(KinesisClient.class, (p, m, a) -> {
                if (m.getName().equals("describeStreamSummary")) return DescribeStreamSummaryResponse.builder()
                    .streamDescriptionSummary(StreamDescriptionSummary.builder().build()).build();
                assertEquals("listStreams", m.getName());
                return ListStreamsResponse.builder().streamNames(empty ? java.util.List.of() : java.util.List.of("repeat")).hasMoreStreams(true).build();
            });
            var section = new ReportSection("Kinesis");
            new KinesisReportCollector().collect(client, section);
            assertEquals(empty ? "FAILED" : "PARTIAL", section.result().status());
            assertEquals("ListStreams", section.result().issues().get(0).operation());
        }
    }
}
