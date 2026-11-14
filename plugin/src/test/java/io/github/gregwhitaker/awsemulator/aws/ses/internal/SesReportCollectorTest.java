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

package io.github.gregwhitaker.awsemulator.aws.ses.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SesReportCollectorTest {
    @Test void paginatesAllInventoriesWithoutRetrievingTemplateOrMessageContent() {
        var client = SdkStub.client(SesV2Client.class, (p, m, a) -> switch (m.getName()) {
            case "listEmailIdentities" -> {
                String token = ((ListEmailIdentitiesRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("identities"));
                yield ListEmailIdentitiesResponse.builder().emailIdentities(IdentityInfo.builder()
                    .identityName(token == null ? "one@example.test" : "two@example.test").identityType(IdentityType.EMAIL_ADDRESS).sendingEnabled(true).build())
                    .nextToken(token == null ? "identities" : null).build();
            }
            case "getEmailIdentity" -> GetEmailIdentityResponse.builder().verifiedForSendingStatus(true).feedbackForwardingStatus(true)
                .dkimAttributes(DkimAttributes.builder().status(DkimStatus.SUCCESS).tokens("private-dkim-token").build())
                .policies(Map.of("private-policy", "private-document")).tags(Tag.builder().key("private-tag").value("private-value").build()).build();
            case "listConfigurationSets" -> {
                String token = ((ListConfigurationSetsRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("sets"));
                yield ListConfigurationSetsResponse.builder().configurationSets(token == null ? "one" : "two").nextToken(token == null ? "sets" : null).build();
            }
            case "getConfigurationSetEventDestinations" -> GetConfigurationSetEventDestinationsResponse.builder()
                .eventDestinations(EventDestination.builder().name("sns").enabled(true).matchingEventTypes(EventType.SEND)
                    .snsDestination(SnsDestination.builder().topicArn("arn:topic").build())
                    .cloudWatchDestination(CloudWatchDestination.builder().dimensionConfigurations(CloudWatchDimensionConfiguration.builder()
                        .dimensionName("private-dimension").defaultDimensionValue("private-value").dimensionValueSource(DimensionValueSource.MESSAGE_TAG).build()).build()).build()).build();
            case "listEmailTemplates" -> {
                String token = ((ListEmailTemplatesRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("templates"));
                yield ListEmailTemplatesResponse.builder().templatesMetadata(EmailTemplateMetadata.builder()
                    .templateName(token == null ? "first" : "second").createdTimestamp(Instant.EPOCH).build())
                    .nextToken(token == null ? "templates" : null).build();
            }
            default -> throw new AssertionError("Unexpected or email-content call: " + m.getName());
        });
        var section = new ReportSection("SES");
        new SesReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(6, section.result().resources().size());
        var set = section.result().resources().stream().filter(r -> r.type().equals("AWS::SES::ConfigurationSet")).findFirst().orElseThrow();
        assertEquals(1, ((List<?>) set.configuration().get("eventDestinations")).size());
        assertTrue(set.configuration().toString().contains("arn:topic"));
        assertTrue(section.result().resources().toString().contains("dkimStatus=SUCCESS"));
        assertFalse(section.result().toString().contains("private-"));
    }

    @Test void independentInventoriesContinueAfterAnUnsupportedIdentityCallAndRepeatedTokensStop() {
        var client = SdkStub.client(SesV2Client.class, (p, m, a) -> switch (m.getName()) {
            case "listEmailIdentities" -> throw SesV2Exception.builder().statusCode(501).message("private-response").build();
            case "listConfigurationSets" -> ListConfigurationSetsResponse.builder().build();
            case "listEmailTemplates" -> ListEmailTemplatesResponse.builder().templatesMetadata(EmailTemplateMetadata.builder().templateName("retained").build())
                .nextToken("repeat").build();
            default -> throw new AssertionError(m.getName());
        });
        var section = new ReportSection("SES");
        new SesReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertEquals(1, section.result().resources().size());
        assertEquals(2, section.result().issues().size());
        assertEquals("UNSUPPORTED", section.result().issues().get(0).kind());
        assertTrue(section.result().issues().get(1).message().contains("Repeated pagination token"));
        assertFalse(section.result().toString().contains("private-response"));
    }
}
