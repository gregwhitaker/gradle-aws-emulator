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

package io.github.gregwhitaker.awsemulator.aws.ssm.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SsmReportCollectorTest {
    @Test void followsEmptyPagesAndSelectsMetadataWithoutReadingValues() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(SsmClient.class, (p, m, a) -> {
            assertEquals("describeParameters", m.getName());
            var request = (DescribeParametersRequest) a[0];
            assertEquals(50, request.maxResults());
            if (calls.incrementAndGet() == 1) {
                assertNull(request.nextToken());
                return DescribeParametersResponse.builder().nextToken("next").build();
            }
            assertEquals("next", request.nextToken());
            return DescribeParametersResponse.builder().parameters(ParameterMetadata.builder().name("/app/password")
                .type(ParameterType.SECURE_STRING).keyId("alias/test").tier(ParameterTier.STANDARD).dataType("text").version(2L)
                .lastModifiedDate(Instant.EPOCH).description("private-description").lastModifiedUser("private-user")
                .allowedPattern("private-pattern").build()).build();
        });
        var section = new ReportSection("SSM");
        new SsmReportCollector().collect(client, section);
        var result = section.result();
        assertEquals("COMPLETE", result.status());
        assertEquals(2, calls.get());
        var metadata = result.resources().get(0).configuration();
        assertEquals("SecureString", metadata.get("type"));
        assertEquals("alias/test", metadata.get("keyId"));
        assertEquals(2L, metadata.get("version"));
        assertEquals("1970-01-01T00:00:00Z", metadata.get("modifiedAt"));
        assertFalse(result.toString().contains("private-"));
    }

    @Test void repeatedTokensPreserveCapturedParametersAndReportAnIssue() {
        var client = SdkStub.client(SsmClient.class, (p, m, a) -> DescribeParametersResponse.builder().nextToken("repeat")
            .parameters(ParameterMetadata.builder().name("/app/setting").build()).build());
        var section = new ReportSection("SSM");
        new SsmReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(1, section.result().resources().size());
        assertTrue(section.result().issues().get(0).message().contains("Repeated pagination token"));
    }
}
