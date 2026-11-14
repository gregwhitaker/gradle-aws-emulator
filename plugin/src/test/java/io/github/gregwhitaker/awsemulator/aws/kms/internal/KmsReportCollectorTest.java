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

package io.github.gregwhitaker.awsemulator.aws.kms.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KmsReportCollectorTest {
    @Test void followsBothMarkerListingsAndNeverResolvesUnboundAliases() {
        var describes = new AtomicInteger();
        var client = SdkStub.client(KmsClient.class, (p, m, a) -> switch (m.getName()) {
            case "listKeys" -> {
                String marker = ((ListKeysRequest) a[0]).marker();
                assertTrue(marker == null || marker.equals("keys"));
                yield ListKeysResponse.builder().keys(KeyListEntry.builder().keyId(marker == null ? "one" : "two").keyArn("arn:key").build())
                    .truncated(marker == null).nextMarker(marker == null ? "keys" : null).build();
            }
            case "describeKey" -> {
                String key = ((DescribeKeyRequest) a[0]).keyId();
                assertTrue(key.equals("one") || key.equals("two"), "Must describe existing key IDs, never aliases");
                describes.incrementAndGet();
                yield DescribeKeyResponse.builder().keyMetadata(KeyMetadata.builder().keyId(key).keyState(KeyState.ENABLED)
                    .enabled(true).keySpec(KeySpec.SYMMETRIC_DEFAULT).keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                    .keyManager(KeyManagerType.CUSTOMER).origin(OriginType.AWS_KMS).description("private-description").build()).build();
            }
            case "listAliases" -> {
                String marker = ((ListAliasesRequest) a[0]).marker();
                assertTrue(marker == null || marker.equals("aliases"));
                yield ListAliasesResponse.builder().aliases(AliasListEntry.builder().aliasName(marker == null ? "alias/app" : "alias/aws/unbound")
                    .targetKeyId(marker == null ? "one" : null).build()).truncated(marker == null).nextMarker(marker == null ? "aliases" : null).build();
            }
            default -> throw new AssertionError("Unexpected or cryptographic call: " + m.getName());
        });
        var section = new ReportSection("KMS");
        new KmsReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(4, section.result().resources().size());
        assertEquals(2, describes.get());
        var unbound = section.result().resources().stream().filter(r -> r.id().equals("alias/aws/unbound")).findFirst().orElseThrow();
        assertFalse(unbound.configuration().containsKey("targetKeyId"));
        assertFalse(section.result().toString().contains("private-description"));
    }

    @Test void reportsMissingMarkersAndStillCollectsAliasesAfterKeyListingFails() {
        var client = SdkStub.client(KmsClient.class, (p, m, a) -> switch (m.getName()) {
            case "listKeys" -> ListKeysResponse.builder().truncated(true).build();
            case "listAliases" -> ListAliasesResponse.builder().aliases(AliasListEntry.builder().aliasName("alias/retained").targetKeyId("key").build())
                .truncated(true).nextMarker("repeat").build();
            default -> throw new AssertionError(m.getName());
        });
        var section = new ReportSection("KMS");
        new KmsReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(1, section.result().resources().size());
        assertEquals(2, section.result().issues().size());
        assertEquals("ListKeys", section.result().issues().get(0).operation());
        assertEquals("ListAliases", section.result().issues().get(1).operation());
    }
}
