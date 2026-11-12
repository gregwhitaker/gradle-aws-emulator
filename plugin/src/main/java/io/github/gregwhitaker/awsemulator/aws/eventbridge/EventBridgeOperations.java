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

package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class EventBridgeOperations {
    static final String BUS = "[A-Za-z0-9_.-]{1,256}";
    static final String RULE = "[A-Za-z0-9_.-]{1,64}";

    void createBus(EventBridgeClient client, String name) {
        try { client.describeEventBus(DescribeEventBusRequest.builder().name(name).build()); }
        catch (ResourceNotFoundException missing) { client.createEventBus(CreateEventBusRequest.builder().name(name).build()); }
    }

    void putRule(EventBridgeClient client, String bus, String rule, String pattern, boolean overwrite) {
        try {
            var existing = client.describeRule(DescribeRuleRequest.builder().eventBusName(bus).name(rule).build());
            if (existing.eventPattern() != null && JsonFixtures.object(existing.eventPattern(), "existing pattern").equals(pattern)
                    && existing.state() == RuleState.ENABLED && existing.scheduleExpression() == null) return;
            if (!overwrite) throw new GradleException("EventBridge rule differs; set overwrite=true to replace its pattern and enable it.");
        } catch (ResourceNotFoundException ignored) { }
        client.putRule(PutRuleRequest.builder().eventBusName(bus).name(rule).eventPattern(pattern).state(RuleState.ENABLED).build());
    }

    void putTargets(EventBridgeClient client, String bus, String rule, Map<String, String> requested, boolean overwrite) {
        var existing = targets(client, bus, rule);
        for (var target : existing) {
            String arn = requested.get(target.id());
            if (arn != null && !target.equals(Target.builder().id(target.id()).arn(arn).build()) && !overwrite) {
                throw new GradleException("Target '" + target.id() + "' differs; set overwrite=true to replace it.");
            }
        }
        var targets = requested.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> Target.builder().id(entry.getKey()).arn(entry.getValue()).build()).toList();
        for (int i = 0; i < targets.size(); i += 10) {
            var result = client.putTargets(PutTargetsRequest.builder().eventBusName(bus).rule(rule)
                .targets(targets.subList(i, Math.min(i + 10, targets.size()))).build());
            if (result.failedEntryCount() != null && result.failedEntryCount() > 0 || !result.failedEntries().isEmpty()) {
                throw new GradleException("EventBridge target update partially failed: " + result.failedEntries().stream().map(e -> e.targetId() + ":" + e.errorCode()).toList());
            }
        }
    }

    List<EventBus> buses(EventBridgeClient client) {
        List<EventBus> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listEventBuses(ListEventBusesRequest.builder().nextToken(token).build());
            result.addAll(page.eventBuses()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    List<Rule> rules(EventBridgeClient client, String bus) {
        List<Rule> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listRules(ListRulesRequest.builder().eventBusName(bus).nextToken(token).build());
            result.addAll(page.rules()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    List<Target> targets(EventBridgeClient client, String bus, String rule) {
        List<Target> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listTargetsByRule(ListTargetsByRuleRequest.builder().eventBusName(bus).rule(rule).nextToken(token).build());
            result.addAll(page.targets()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    void deleteRule(EventBridgeClient client, String bus, String rule) {
        try {
            var targets = targets(client, bus, rule).stream().map(Target::id).toList();
            for (int i = 0; i < targets.size(); i += 10) {
                var result = client.removeTargets(RemoveTargetsRequest.builder().eventBusName(bus).rule(rule)
                    .ids(targets.subList(i, Math.min(i + 10, targets.size()))).build());
                if (result.failedEntryCount() != null && result.failedEntryCount() > 0 || !result.failedEntries().isEmpty()) throw new GradleException("Some EventBridge targets could not be removed; rule was retained.");
            }
            client.deleteRule(DeleteRuleRequest.builder().eventBusName(bus).name(rule).build());
        } catch (ResourceNotFoundException ignored) { }
    }

    void publish(EventBridgeClient client, String bus, List<PutEventsRequestEntry> events) {
        // PutEvents can silently discard events for nonexistent buses; verify the bus first.
        client.describeEventBus(DescribeEventBusRequest.builder().name(bus).build());
        int offset = 0;
        while (offset < events.size()) {
            int end = offset, bytes = 0;
            while (end < events.size() && end - offset < 10) {
                var event = events.get(end);
                int size = event.source().getBytes(StandardCharsets.UTF_8).length + event.detailType().getBytes(StandardCharsets.UTF_8).length
                    + event.detail().getBytes(StandardCharsets.UTF_8).length + 128;
                if (size >= 256 * 1024) throw new GradleException("Event fixture exceeds the supported 256 KiB entry limit.");
                if (bytes + size >= 256 * 1024) break;
                bytes += size; end++;
            }
            var result = client.putEvents(PutEventsRequest.builder().entries(events.subList(offset, end)).build());
            if (result.entries().size() != end - offset) throw new GradleException("EventBridge returned an incomplete publish result.");
            List<Integer> failed = new ArrayList<>();
            for (int i = 0; i < result.entries().size(); i++) if (result.entries().get(i).errorCode() != null) failed.add(offset + i + 1);
            if (!failed.isEmpty() || result.failedEntryCount() != null && result.failedEntryCount() > 0) {
                throw new GradleException("EventBridge publish partially failed at fixture entries " + failed + ". Successful events were retained; rerunning can duplicate them.");
            }
            offset = end;
        }
    }
}
