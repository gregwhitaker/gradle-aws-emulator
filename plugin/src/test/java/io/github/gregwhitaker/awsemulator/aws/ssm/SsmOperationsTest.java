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
