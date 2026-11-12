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

import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class KmsOperationsTest {
    @Test void reusesEnabledSymmetricKeyWithoutCreatingAnother() {
        var sdk = client(KmsClient.class, (p, m, a) -> {
            assertEquals("describeKey", m.getName());
            return DescribeKeyResponse.builder().keyMetadata(KeyMetadata.builder().keyId("key")
                .keyState(KeyState.ENABLED).keySpec(KeySpec.SYMMETRIC_DEFAULT).keyUsage(KeyUsageType.ENCRYPT_DECRYPT).build()).build();
        });
        assertEquals("key", new KmsOperations().create(sdk, "alias/test"));
    }
    @Test void scheduledDeletionIsNotRescheduled() {
        var sdk = client(KmsClient.class, (p, m, a) -> {
            assertEquals("describeKey", m.getName());
            return DescribeKeyResponse.builder().keyMetadata(KeyMetadata.builder().keyState(KeyState.PENDING_DELETION).build()).build();
        });
        new KmsOperations().scheduleDeletion(sdk, "key", 7);
        assertThrows(GradleException.class, () -> KmsOperations.alias("alias/aws/ssm"));
    }
}
