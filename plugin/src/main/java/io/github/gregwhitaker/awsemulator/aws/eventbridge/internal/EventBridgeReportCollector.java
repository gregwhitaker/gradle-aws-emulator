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

package io.github.gregwhitaker.awsemulator.aws.eventbridge.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads buses, rules, and target relationships without event patterns, payloads, or input transforms. */
public final class EventBridgeReportCollector {
    public void collect(EventBridgeClient client, ReportSection section) {
        section.read("ListEventBuses", null, () -> pages(token -> {
            var page = client.listEventBuses(ListEventBusesRequest.builder().limit(100).nextToken(token).build());
            for (var bus : page.eventBuses()) {
                String name = bus.name();
                var details = section.resource("AWS::Events::EventBus", name);
                details.putAll(fields("name", name, "arn", bus.arn()));
                var rules = new ArrayList<Map<String, Object>>();
                details.put("rules", rules);
                section.read("ListRules", name, () -> pages(next -> {
                    var response = client.listRules(ListRulesRequest.builder().eventBusName(name).limit(100).nextToken(next).build());
                    for (var rule : response.rules()) {
                        var configuration = fields("name", rule.name(), "arn", rule.arn(), "state", rule.stateAsString(),
                            "scheduleExpression", rule.scheduleExpression(), "roleArn", rule.roleArn(), "managedBy", rule.managedBy());
                        rules.add(configuration);
                        var targets = new ArrayList<Map<String, Object>>();
                        configuration.put("targets", targets);
                        section.read("ListTargetsByRule", name + "/" + rule.name(), () -> pages(targetToken -> {
                            var targetPage = client.listTargetsByRule(ListTargetsByRuleRequest.builder()
                                .eventBusName(name).rule(rule.name()).limit(100).nextToken(targetToken).build());
                            for (var target : targetPage.targets()) {
                                var retry = target.retryPolicy();
                                targets.add(fields("id", target.id(), "arn", target.arn(), "roleArn", target.roleArn(),
                                    "deadLetterArn", target.deadLetterConfig() == null ? null : target.deadLetterConfig().arn(),
                                    "maximumEventAgeInSeconds", retry == null ? null : retry.maximumEventAgeInSeconds(),
                                    "maximumRetryAttempts", retry == null ? null : retry.maximumRetryAttempts()));
                            }
                            return targetPage.nextToken();
                        }));
                        targets.sort(Comparator.comparing(Object::toString));
                    }
                    return response.nextToken();
                }));
                rules.sort(Comparator.comparing(Object::toString));
            }
            return page.nextToken();
        }));
    }
}
