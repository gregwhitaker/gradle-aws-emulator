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
