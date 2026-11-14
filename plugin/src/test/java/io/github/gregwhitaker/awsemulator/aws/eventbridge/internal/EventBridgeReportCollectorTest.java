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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EventBridgeReportCollectorTest {
    @Test void followsBusRuleAndTargetPagesWithoutExportingPatternsOrPayloads() {
        var client = SdkStub.client(EventBridgeClient.class, (p, m, a) -> switch (m.getName()) {
            case "listEventBuses" -> {
                String token = ((ListEventBusesRequest) a[0]).nextToken();
                assertTrue(token == null || token.equals("buses"));
                yield ListEventBusesResponse.builder().eventBuses(EventBus.builder().name(token == null ? "one" : "two").arn("arn:bus")
                    .description("private-description").policy("private-policy").build()).nextToken(token == null ? "buses" : null).build();
            }
            case "listRules" -> {
                var request = (ListRulesRequest) a[0];
                assertTrue(List.of("one", "two").contains(request.eventBusName()));
                assertTrue(request.nextToken() == null || request.nextToken().equals("rules"));
                yield ListRulesResponse.builder().rules(Rule.builder().name(request.nextToken() == null ? "first" : "second")
                    .state(RuleState.ENABLED).scheduleExpression("rate(1 hour)").eventPattern("private-pattern").build())
                    .nextToken(request.nextToken() == null ? "rules" : null).build();
            }
            case "listTargetsByRule" -> {
                var request = (ListTargetsByRuleRequest) a[0];
                assertTrue(List.of("one", "two").contains(request.eventBusName()));
                assertTrue(List.of("first", "second").contains(request.rule()));
                assertTrue(request.nextToken() == null || request.nextToken().equals("targets"));
                yield ListTargetsByRuleResponse.builder().targets(Target.builder().id(request.nextToken() == null ? "a" : "b").arn("arn:queue")
                    .input("private-input").inputPath("private-path")
                    .inputTransformer(InputTransformer.builder().inputTemplate("private-template").inputPathsMap(Map.of("private", "$.private")).build())
                    .retryPolicy(RetryPolicy.builder().maximumRetryAttempts(2).build())
                    .deadLetterConfig(DeadLetterConfig.builder().arn("arn:dlq").build()).build())
                    .nextToken(request.nextToken() == null ? "targets" : null).build();
            }
            default -> throw new AssertionError("Unexpected or mutating call: " + m.getName());
        });
        var section = new ReportSection("EventBridge");
        new EventBridgeReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        var rules = (List<?>) section.result().resources().get(0).configuration().get("rules");
        assertEquals(2, rules.size());
        assertEquals(2, ((List<?>) ((Map<?, ?>) rules.get(0)).get("targets")).size());
        assertTrue(rules.toString().contains("maximumRetryAttempts=2"));
        assertTrue(rules.toString().contains("arn:dlq"));
        assertFalse(section.result().toString().contains("private-"));
    }

    @Test void aTargetFailureDoesNotHideOtherRulesAndRepeatedBusTokensStop() {
        var client = SdkStub.client(EventBridgeClient.class, (p, m, a) -> switch (m.getName()) {
            case "listEventBuses" -> ListEventBusesResponse.builder().eventBuses(EventBus.builder().name("bus").build()).nextToken("repeat").build();
            case "listRules" -> ListRulesResponse.builder().rules(Rule.builder().name("bad").build(), Rule.builder().name("good").build()).build();
            case "listTargetsByRule" -> {
                if (((ListTargetsByRuleRequest) a[0]).rule().equals("bad")) throw EventBridgeException.builder().message("private-response").statusCode(403).build();
                yield ListTargetsByRuleResponse.builder().targets(Target.builder().id("retained").arn("arn:queue").build()).build();
            }
            default -> throw new AssertionError(m.getName());
        });
        var section = new ReportSection("EventBridge");
        new EventBridgeReportCollector().collect(client, section);
        assertEquals("PARTIAL", section.result().status());
        assertTrue(section.result().resources().toString().contains("retained"));
        assertTrue(section.result().issues().stream().anyMatch(i -> "bus/bad".equals(i.resource())));
        assertTrue(section.result().issues().stream().anyMatch(i -> i.message().contains("Repeated pagination token")));
        assertFalse(section.result().toString().contains("private-response"));
    }
}
