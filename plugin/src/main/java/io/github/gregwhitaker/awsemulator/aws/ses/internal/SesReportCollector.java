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
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads identities, configuration sets, event destinations, and template names without email content. */
public final class SesReportCollector {
    public void collect(SesV2Client client, ReportSection section) {
        section.read("ListEmailIdentities", null, () -> pages(token -> {
            var page = client.listEmailIdentities(ListEmailIdentitiesRequest.builder().pageSize(100).nextToken(token).build());
            for (var identity : page.emailIdentities()) {
                String name = identity.identityName();
                var details = section.resource("AWS::SES::EmailIdentity", name);
                details.putAll(fields("name", name, "type", identity.identityTypeAsString(), "sendingEnabled", identity.sendingEnabled()));
                section.read("GetEmailIdentity", name, () -> {
                    var metadata = client.getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(name).build());
                    details.putAll(fields("verifiedForSending", metadata.verifiedForSendingStatus(),
                        "feedbackForwardingEnabled", metadata.feedbackForwardingStatus(),
                        "dkimStatus", metadata.dkimAttributes() == null ? null : metadata.dkimAttributes().statusAsString(),
                        "mailFromDomain", metadata.mailFromAttributes() == null ? null : metadata.mailFromAttributes().mailFromDomain()));
                });
            }
            return page.nextToken();
        }));
        section.read("ListConfigurationSets", null, () -> pages(token -> {
            var page = client.listConfigurationSets(ListConfigurationSetsRequest.builder().pageSize(100).nextToken(token).build());
            for (String name : page.configurationSets()) {
                var details = section.resource("AWS::SES::ConfigurationSet", name);
                details.put("name", name);
                var destinations = new ArrayList<Map<String, Object>>();
                details.put("eventDestinations", destinations);
                section.read("GetConfigurationSetEventDestinations", name, () -> {
                    var response = client.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder().configurationSetName(name).build());
                    for (var destination : response.eventDestinations()) destinations.add(fields(
                        "name", destination.name(), "enabled", destination.enabled(),
                        "matchingEventTypes", destination.matchingEventTypesAsStrings().stream().sorted().toList(),
                        "snsTopicArn", destination.snsDestination() == null ? null : destination.snsDestination().topicArn(),
                        "eventBusArn", destination.eventBridgeDestination() == null ? null : destination.eventBridgeDestination().eventBusArn(),
                        "firehoseStreamArn", destination.kinesisFirehoseDestination() == null ? null : destination.kinesisFirehoseDestination().deliveryStreamArn()));
                });
                destinations.sort(Comparator.comparing(Object::toString));
            }
            return page.nextToken();
        }));
        section.read("ListEmailTemplates", null, () -> pages(token -> {
            var page = client.listEmailTemplates(ListEmailTemplatesRequest.builder().pageSize(100).nextToken(token).build());
            for (var template : page.templatesMetadata()) section.resource("AWS::SES::Template", template.templateName()).putAll(fields(
                "name", template.templateName(), "createdAt", timestamp(template.createdTimestamp())));
            return page.nextToken();
        }));
    }
}
