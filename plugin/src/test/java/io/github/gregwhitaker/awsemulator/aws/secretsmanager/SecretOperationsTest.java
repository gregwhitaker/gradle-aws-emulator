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

package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class SecretOperationsTest {
    @Test void unchangedSecretsDoNotWriteVersionsAndUpdatesAreExplicit() {
        AtomicReference<UpdateSecretRequest> write = new AtomicReference<>();
        var sdk = client(SecretsManagerClient.class, (p, m, a) -> switch (m.getName()) {
            case "describeSecret" -> DescribeSecretResponse.builder().name("test").build();
            case "getSecretValue" -> GetSecretValueResponse.builder().secretString("old").build();
            case "updateSecret" -> { write.set((UpdateSecretRequest) a[0]); yield UpdateSecretResponse.builder().build(); }
            default -> throw new AssertionError(m);
        });
        var ops = new SecretOperations();
        ops.put(sdk, new SecretOperations.Secret("test", "old", null), true); assertNull(write.get());
        assertThrows(GradleException.class, () -> ops.put(sdk, new SecretOperations.Secret("test", "new", null), false));
        ops.put(sdk, new SecretOperations.Secret("test", "new", null), true);
        assertEquals("new", write.get().secretString());
    }
    @Test void pendingDeletionIsNotSilentlyRestored() {
        var sdk = client(SecretsManagerClient.class, (p, m, a) -> DescribeSecretResponse.builder().deletedDate(Instant.now()).build());
        assertThrows(GradleException.class, () -> new SecretOperations().put(sdk, new SecretOperations.Secret("test", "value", null), true));
    }
    @Test void forceDeletionOmitsRecoveryWindow() {
        AtomicReference<DeleteSecretRequest> request = new AtomicReference<>();
        var sdk = client(SecretsManagerClient.class, (p, m, a) -> {
            if (m.getName().equals("describeSecret")) return DescribeSecretResponse.builder().build();
            request.set((DeleteSecretRequest) a[0]); return DeleteSecretResponse.builder().build();
        });
        new SecretOperations().delete(sdk, "test", true, 30);
        assertTrue(request.get().forceDeleteWithoutRecovery()); assertNull(request.get().recoveryWindowInDays());
        new SecretOperations().delete(sdk, "test", false, 7);
        assertEquals(7L, request.get().recoveryWindowInDays()); assertNull(request.get().forceDeleteWithoutRecovery());
    }
}
