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
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads existing keys and alias mappings without exporting key material or resolving unbound AWS aliases. */
public final class KmsReportCollector {
    public void collect(KmsClient client, ReportSection section) {
        section.read("ListKeys", null, () -> pages(marker -> {
            var page = client.listKeys(ListKeysRequest.builder().limit(100).marker(marker).build());
            for (var key : page.keys()) {
                var details = section.resource("AWS::KMS::Key", key.keyId());
                details.putAll(fields("keyId", key.keyId(), "arn", key.keyArn()));
                section.read("DescribeKey", key.keyId(), () -> {
                    var metadata = client.describeKey(DescribeKeyRequest.builder().keyId(key.keyId()).build()).keyMetadata();
                    details.putAll(fields("arn", metadata.arn(), "enabled", metadata.enabled(), "state", metadata.keyStateAsString(),
                        "keySpec", metadata.keySpecAsString(), "keyUsage", metadata.keyUsageAsString(),
                        "keyManager", metadata.keyManagerAsString(), "origin", metadata.originAsString(),
                        "multiRegion", metadata.multiRegion(), "createdAt", timestamp(metadata.creationDate()),
                        "deletionDate", timestamp(metadata.deletionDate())));
                });
            }
            return next(page.truncated(), page.nextMarker());
        }));
        section.read("ListAliases", null, () -> pages(marker -> {
            var page = client.listAliases(ListAliasesRequest.builder().limit(100).marker(marker).build());
            for (var alias : page.aliases()) section.resource("AWS::KMS::Alias", alias.aliasName()).putAll(fields(
                "name", alias.aliasName(), "arn", alias.aliasArn(), "targetKeyId", alias.targetKeyId(),
                "createdAt", timestamp(alias.creationDate()), "updatedAt", timestamp(alias.lastUpdatedDate())));
            return next(page.truncated(), page.nextMarker());
        }));
    }

    private String next(Boolean truncated, String marker) {
        if (!Boolean.TRUE.equals(truncated)) return null;
        if (marker == null || marker.isEmpty()) throw new IllegalStateException("Truncated listing has no continuation marker.");
        return marker;
    }
}
