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

package io.github.gregwhitaker.awsemulator.aws.kinesis;

import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class KinesisOperationsTest {
    @Test void reportsFailedRecordIndexesWithoutReplaying() {
        AtomicInteger calls = new AtomicInteger();
        var sdk = client(KinesisClient.class, (p, m, a) -> {
            calls.incrementAndGet(); return PutRecordsResponse.builder().failedRecordCount(1).records(
                PutRecordsResultEntry.builder().sequenceNumber("1").shardId("s").build(),
                PutRecordsResultEntry.builder().errorCode("ProvisionedThroughputExceededException").build()).build();
        });
        var record = PutRecordsRequestEntry.builder().partitionKey("key").data(SdkBytes.fromUtf8String("value")).build();
        var error = assertThrows(GradleException.class, () -> new KinesisOperations().publish(sdk, "stream", List.of(record, record)));
        assertTrue(error.getMessage().contains("[2]")); assertEquals(1, calls.get());
    }
    @Test void emptyTruncatedListingFailsInsteadOfLooping() {
        var sdk = client(KinesisClient.class, (p, m, a) -> ListStreamsResponse.builder().hasMoreStreams(true).build());
        assertThrows(GradleException.class, () -> new KinesisOperations().list(sdk));
    }
}
