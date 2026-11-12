package io.github.gregwhitaker.awsemulator.aws.ssm;

import org.junit.jupiter.api.Test;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class SsmOperationsTest {
    @Test void unchangedValuesDoNotCreateVersionsAndConflictsNeedExplicitOverwrite() {
        AtomicReference<PutParameterRequest> write = new AtomicReference<>();
        var sdk = client(SsmClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "getParameter" -> GetParameterResponse.builder().parameter(Parameter.builder().name("/test/name").value("old").type(ParameterType.STRING).version(4L).build()).build();
            case "putParameter" -> { write.set((PutParameterRequest) args[0]); yield PutParameterResponse.builder().version(5L).build(); }
            default -> throw new AssertionError(method);
        });
        var ops = new SsmOperations();
        ops.put(sdk, new SsmOperations.Parameter("/test/name", "old", "String", null), false);
        assertNull(write.get());
        assertThrows(GradleException.class, () -> ops.put(sdk, new SsmOperations.Parameter("/test/name", "new", "String", null), false));
        assertNull(write.get());
        ops.put(sdk, new SsmOperations.Parameter("/test/name", "new", "String", null), true);
        assertTrue(write.get().overwrite()); assertEquals("new", write.get().value());
    }
    @Test void serviceErrorDoesNotExposeSecretValuesOrRawCauses() {
        var sdk = client(SsmClient.class, (p, m, a) -> { throw SsmException.builder().message("leaked-value")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationException").build()).build(); });
        var error = assertThrows(GradleException.class, () -> new SsmOperations().put(sdk,
            new SsmOperations.Parameter("/test/name", "leaked-value", "String", null), false));
        assertFalse(error.toString().contains("leaked-value")); assertNull(error.getCause());
    }
    @Test void listingRejectsRepeatedTokensAndValidationRejectsInvalidMetadata() {
        var sdk = client(SsmClient.class, (p, m, a) -> DescribeParametersResponse.builder().nextToken("repeated").build());
        assertThrows(GradleException.class, () -> new SsmOperations().list(sdk));
        assertThrows(GradleException.class, () -> new SsmOperations.Parameter("/test/name", "v", "String", "alias/key"));
        assertThrows(GradleException.class, () -> new SsmOperations.Parameter("/aws/name", "v", "String", null));
    }
}
