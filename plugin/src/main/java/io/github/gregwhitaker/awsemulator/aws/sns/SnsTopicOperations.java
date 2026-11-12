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

package io.github.gregwhitaker.awsemulator.aws.sns;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves actual service ARNs, traverses all pages, and verifies requested topic attributes. */
final class SnsTopicOperations {
    static void validateName(String name) {
        if (name == null || name.length() > 256 || !name.matches("[A-Za-z0-9_-]+(\\.fifo)?")) {
            throw new GradleException("topicName must contain 1–256 letters, numbers, underscores or hyphens, with an optional .fifo suffix.");
        }
    }

    static void validate(String name, Map<String, String> attributes) {
        validateName(name);
        validateAttributes(attributes, "SNS topic");
        String fifo = attributes.getOrDefault("FifoTopic", "false");
        if ((!fifo.equals("true") && !fifo.equals("false")) || name.endsWith(".fifo") != fifo.equals("true")) {
            throw new GradleException("FIFO topics require both a .fifo topicName and the FifoTopic attribute set to 'true'.");
        }
    }

    static void validateAttributes(Map<String, String> attributes, String label) {
        if (attributes.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new GradleException(label + " attribute names and values must not be blank.");
        }
    }

    String provision(SnsClient client, String name, Map<String, String> attributes) {
        validate(name, attributes);
        String arn = find(client, name);
        if (arn == null) {
            arn = client.createTopic(CreateTopicRequest.builder().name(name).attributes(attributes).build()).topicArn();
            if (arn == null || arn.isBlank()) throw new GradleException("SNS did not return a topic ARN.");
        }
        // CreateTopic is idempotent: a concurrent creator may have chosen different attributes.
        if (!attributes.isEmpty()) {
            var actual = client.getTopicAttributes(GetTopicAttributesRequest.builder().topicArn(arn).build()).attributes();
            for (var entry : attributes.entrySet()) {
                String value = entry.getKey().equals("FifoTopic") ? actual.getOrDefault(entry.getKey(), "false") : actual.get(entry.getKey());
                if (!Objects.equals(entry.getValue(), value)) {
                    throw new GradleException("SNS topic '" + name + "' has a different " + entry.getKey()
                        + " attribute. Existing topics are not modified; choose another name or restart the emulator.");
                }
            }
        }
        return arn;
    }

    List<Topic> list(SnsClient client) {
        var topics = new ArrayList<Topic>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listTopics(ListTopicsRequest.builder().nextToken(token).build());
            topics.addAll(page.topics());
            token = next(page.nextToken(), tokens);
        } while (token != null);
        return topics;
    }

    String find(SnsClient client, String name) {
        return list(client).stream().map(Topic::topicArn).filter(arn -> name.equals(name(arn))).findFirst().orElse(null);
    }

    String require(SnsClient client, String name) {
        String arn = find(client, name);
        if (arn == null) throw new GradleException("SNS topic '" + name + "' does not exist.");
        return arn;
    }

    List<Subscription> subscriptions(SnsClient client, String arn) {
        var subscriptions = new ArrayList<Subscription>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listSubscriptionsByTopic(ListSubscriptionsByTopicRequest.builder().topicArn(arn).nextToken(token).build());
            subscriptions.addAll(page.subscriptions());
            token = next(page.nextToken(), tokens);
        } while (token != null);
        return subscriptions;
    }

    void delete(SnsClient client, String name) {
        String arn = find(client, name);
        if (arn != null) {
            try { client.deleteTopic(DeleteTopicRequest.builder().topicArn(arn).build()); }
            catch (NotFoundException ignored) { /* A concurrently removed topic is already absent. */ }
        }
    }

    static String name(String arn) { return arn.substring(arn.lastIndexOf(':') + 1); }

    private static String next(String token, Set<String> seen) {
        if (token == null || token.isEmpty()) return null;
        if (!seen.add(token)) throw new GradleException("SNS listing returned a repeated continuation token.");
        return token;
    }
}
