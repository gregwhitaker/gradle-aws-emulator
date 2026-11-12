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

package io.github.gregwhitaker.awsemulator.aws.kms;

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import java.util.*;

final class KmsOperations {
    static String alias(String name) {
        ServiceSupport.name(name, "alias/[A-Za-z0-9/_-]{1,250}", "aliasName");
        if (name.startsWith("alias/aws/")) throw new GradleException("AWS-managed aliases cannot be provisioned.");
        return name;
    }
    String create(KmsClient client, String alias) {
        try {
            var existing = client.describeKey(DescribeKeyRequest.builder().keyId(alias).build()).keyMetadata();
            if (existing.keyState() != KeyState.ENABLED || existing.keySpec() != KeySpec.SYMMETRIC_DEFAULT
                    || existing.keyUsage() != KeyUsageType.ENCRYPT_DECRYPT) throw new GradleException("Existing alias must identify an enabled symmetric encryption key.");
            return existing.keyId();
        } catch (NotFoundException missing) {
            var key = client.createKey(CreateKeyRequest.builder().keySpec(KeySpec.SYMMETRIC_DEFAULT).keyUsage(KeyUsageType.ENCRYPT_DECRYPT).build()).keyMetadata();
            try { client.createAlias(CreateAliasRequest.builder().aliasName(alias).targetKeyId(key.keyId()).build()); }
            catch (RuntimeException failure) {
                throw new GradleException("Created KMS key " + key.keyId() + " but could not attach alias " + alias + "; inspect and schedule deletion of the unused key before retrying.", failure);
            }
            return key.keyId();
        }
    }
    void alias(KmsClient client, String name, String target, boolean overwrite) {
        String id = client.describeKey(DescribeKeyRequest.builder().keyId(target).build()).keyMetadata().keyId();
        try {
            String existing = client.describeKey(DescribeKeyRequest.builder().keyId(name).build()).keyMetadata().keyId();
            if (id.equals(existing)) return;
            if (!overwrite) throw new GradleException("Alias identifies another key; set overwrite=true to repoint it.");
            client.updateAlias(UpdateAliasRequest.builder().aliasName(name).targetKeyId(id).build());
        } catch (NotFoundException missing) { client.createAlias(CreateAliasRequest.builder().aliasName(name).targetKeyId(id).build()); }
    }
    List<KeyListEntry> list(KmsClient client) {
        List<KeyListEntry> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listKeys(ListKeysRequest.builder().marker(token).limit(100).build());
            result.addAll(page.keys());
            if (!Boolean.TRUE.equals(page.truncated())) return result;
            token = ServiceSupport.next(page.nextMarker(), seen);
            if (token == null) throw new GradleException("KMS listing was truncated without a continuation marker.");
        } while (true);
    }
    void scheduleDeletion(KmsClient client, String key, int days) {
        try {
            var metadata = client.describeKey(DescribeKeyRequest.builder().keyId(key).build()).keyMetadata();
            if (metadata.keyState() == KeyState.PENDING_DELETION) return;
            client.scheduleKeyDeletion(ScheduleKeyDeletionRequest.builder().keyId(metadata.keyId()).pendingWindowInDays(days).build());
        } catch (NotFoundException ignored) { }
    }
}
