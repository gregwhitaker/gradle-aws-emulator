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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists all subscriptions for an existing topic. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListSnsTopicSubscriptionsTask extends AbstractSnsTopicTask {
    @TaskAction
    public void listSubscriptions() {
        String name = topicName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            var operations = new SnsTopicOperations();
            var subscriptions = operations.subscriptions(client, operations.require(client, name));
            getLogger().lifecycle("SubscriptionArn\tProtocol\tEndpoint");
            subscriptions.forEach(subscription -> getLogger().lifecycle("{}\t{}\t{}",
                subscription.subscriptionArn(), subscription.protocol(), subscription.endpoint()));
            if (subscriptions.isEmpty()) getLogger().lifecycle("No Subscriptions Found!");
        }
    }
}
