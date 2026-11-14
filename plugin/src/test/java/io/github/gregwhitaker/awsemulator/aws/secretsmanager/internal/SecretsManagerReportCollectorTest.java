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

package io.github.gregwhitaker.awsemulator.aws.secretsmanager.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SecretsManagerReportCollectorTest {
    @Test void listsEveryPageWithoutReadingSecretValuesOrExportingArbitraryFields() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(SecretsManagerClient.class, (p, m, a) -> {
            assertEquals("listSecrets", m.getName());
            String token = ((ListSecretsRequest) a[0]).nextToken();
            assertEquals(calls.get() == 0 ? null : "next", token);
            calls.incrementAndGet();
            return ListSecretsResponse.builder().nextToken(token == null ? "next" : null)
                .secretList(SecretListEntry.builder().arn("arn:secret:" + calls.get()).name("secret-" + calls.get())
                    .kmsKeyId("key").rotationEnabled(true).rotationLambdaARN("arn:lambda:rotation").createdDate(Instant.EPOCH)
                    .description("private-description").tags(Tag.builder().key("private-tag").value("private-value").build())
                    .secretVersionsToStages(Map.of("private-version", List.of("private-stage"))).build()).build();
        });
        var section = new ReportSection("Secrets Manager");
        new SecretsManagerReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        assertEquals(true, section.result().resources().get(0).configuration().get("rotationEnabled"));
        assertEquals("key", section.result().resources().get(0).configuration().get("kmsKeyId"));
        assertFalse(section.result().toString().contains("private-"));
    }

    @Test void laterPageFailureKeepsMetadataAndOmitsExceptionContents() {
        var client = SdkStub.client(SecretsManagerClient.class, (p, m, a) -> {
            if (((ListSecretsRequest) a[0]).nextToken() != null) throw SecretsManagerException.builder().message("private-response").statusCode(403).build();
            return ListSecretsResponse.builder().secretList(SecretListEntry.builder().arn("arn:secret:first").name("first").build()).nextToken("next").build();
        });
        var section = new ReportSection("Secrets Manager");
        new SecretsManagerReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(1, section.result().resources().size());
        assertEquals("ListSecrets", section.result().issues().get(0).operation());
        assertFalse(section.result().toString().contains("private-response"));
    }
}
