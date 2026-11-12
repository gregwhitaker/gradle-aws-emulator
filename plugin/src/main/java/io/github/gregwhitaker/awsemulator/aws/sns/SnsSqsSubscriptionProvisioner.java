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

import io.github.gregwhitaker.awsemulator.aws.sqs.SqsQueueProvisioner;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.GetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Preserves queue contents and policy statements and reuses an existing SQS subscription. */
final class SnsSqsSubscriptionProvisioner {
    static String defaultQueueName(String topic) {
        return topic.endsWith(".fifo") ? topic.substring(0, topic.length() - 5) + "-subscriber.fifo" : topic + "-subscriber";
    }

    record Settings(String topicName, Map<String, String> topicAttributes, String queueName,
                    Map<String, String> queueAttributes, Map<String, String> subscriptionAttributes) {
        Settings {
            topicAttributes = Map.copyOf(topicAttributes);
            var effectiveQueueAttributes = new HashMap<>(queueAttributes);
            if (queueName.endsWith(".fifo")) effectiveQueueAttributes.putIfAbsent("FifoQueue", "true");
            queueAttributes = Map.copyOf(effectiveQueueAttributes);
            subscriptionAttributes = Map.copyOf(subscriptionAttributes);
            SnsTopicOperations.validate(topicName, topicAttributes);
            SqsQueueProvisioner.validate(queueName, queueAttributes);
            if (!topicName.endsWith(".fifo") && queueName.endsWith(".fifo")) {
                throw new GradleException("A standard SNS topic cannot subscribe an SQS FIFO queue; use a FIFO topic or a standard queue.");
            }
            if (queueAttributes.containsKey("Policy")) {
                throw new GradleException("queueAttributes must not include Policy; the SNS task preserves the existing queue policy and adds its delivery grant.");
            }
            SnsTopicOperations.validateAttributes(subscriptionAttributes, "SNS subscription");
            String raw = subscriptionAttributes.getOrDefault("RawMessageDelivery", "false");
            if (!raw.equals("true") && !raw.equals("false")) {
                throw new GradleException("RawMessageDelivery must be 'true' or 'false'.");
            }
        }
    }

    String provision(SnsClient sns, SqsClient sqs, Settings settings) {
        var topics = new SnsTopicOperations();
        String topicArn = topics.provision(sns, settings.topicName(), settings.topicAttributes());
        String queueUrl = new SqsQueueProvisioner().provision(sqs, settings.queueName(), settings.queueAttributes());
        var queueAttributes = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
            .attributeNames(QueueAttributeName.QUEUE_ARN, QueueAttributeName.POLICY).build()).attributesAsStrings();
        String queueArn = queueAttributes.get("QueueArn");
        if (queueArn == null || queueArn.isBlank()) throw new GradleException("SQS did not return the subscriber queue ARN.");
        String oldPolicy = queueAttributes.get("Policy");
        String policy = SnsQueuePolicy.grant(oldPolicy, topicArn, queueArn);
        if (!Objects.equals(oldPolicy, policy)) {
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributesWithStrings(Map.of("Policy", policy)).build());
        }

        var existing = topics.subscriptions(sns, topicArn).stream()
            .filter(subscription -> "sqs".equals(subscription.protocol()) && queueArn.equals(subscription.endpoint()))
            .findFirst();
        if (existing.isPresent()) {
            String arn = existing.get().subscriptionArn();
            if (!settings.subscriptionAttributes().isEmpty()) {
                var actual = sns.getSubscriptionAttributes(GetSubscriptionAttributesRequest.builder().subscriptionArn(arn).build()).attributes();
                settings.subscriptionAttributes().forEach((key, value) -> {
                    if (!Objects.equals(actual.get(key), value)) {
                        sns.setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder().subscriptionArn(arn)
                            .attributeName(key).attributeValue(value).build());
                    }
                });
            }
            return arn;
        }
        String arn = sns.subscribe(SubscribeRequest.builder().topicArn(topicArn).protocol("sqs").endpoint(queueArn)
            .attributes(settings.subscriptionAttributes()).returnSubscriptionArn(true).build()).subscriptionArn();
        if (arn == null || arn.isBlank()) throw new GradleException("SNS did not return a subscription ARN.");
        return arn;
    }
}
