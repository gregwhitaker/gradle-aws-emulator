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

package io.github.gregwhitaker.awsemulator.aws.s3.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class S3ReportCollectorTest {
    @Test void followsPagesAndKeepsBucketsWhenIndividualMetadataReadsFail() {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "listBuckets" -> {
                var token = ((ListBucketsRequest) args[0]).continuationToken();
                assertTrue(token == null || token.equals("page2"));
                yield ListBucketsResponse.builder().buckets(Bucket.builder().name(token == null ? "one" : "two").build())
                    .continuationToken(token == null ? "page2" : null).build();
            }
            case "getBucketLocation" -> GetBucketLocationResponse.builder().build();
            case "getBucketVersioning" -> {
                if (((GetBucketVersioningRequest) args[0]).bucket().equals("two")) throw S3Exception.builder().statusCode(501).build();
                yield GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build();
            }
            default -> throw new AssertionError("Unexpected or mutating call: " + method.getName());
        });
        var section = new ReportSection("S3");
        new S3ReportCollector().collect(client, section);
        assertEquals(List.of("one", "two"), section.result().resources().stream().map(ReportSection.Resource::id).toList());
        assertEquals("us-east-1", section.result().resources().get(0).configuration().get("region"));
        assertEquals("Enabled", section.result().resources().get(0).configuration().get("versioning"));
        assertEquals("PARTIAL", section.result().status());
        assertEquals("two", section.result().issues().get(0).resource());
    }
}
