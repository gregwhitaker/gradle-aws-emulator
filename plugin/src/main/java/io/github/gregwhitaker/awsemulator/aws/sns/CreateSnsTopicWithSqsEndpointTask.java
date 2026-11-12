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

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates or reuses a topic and queue, grants delivery permission, and subscribes the queue. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSnsTopicWithSqsEndpointTask extends AbstractSnsTopicTask {
    public CreateSnsTopicWithSqsEndpointTask() {
        getTopicAttributes().convention(Map.of());
        getQueueAttributes().convention(Map.of());
        getSubscriptionAttributes().convention(Map.of());
        getQueueName().convention(getTopicName().map(SnsSqsSubscriptionProvisioner::defaultQueueName));
    }

    @Input public abstract MapProperty<String, String> getTopicAttributes();

    /** Defaults to topicName + "-subscriber", before the .fifo suffix for FIFO topics. */
    @Input public abstract Property<String> getQueueName();

    /** Requested SQS attributes, excluding Policy. FifoQueue=true is inferred for .fifo queues. */
    @Input public abstract MapProperty<String, String> getQueueAttributes();

    /** SNS subscription attributes, such as RawMessageDelivery and FilterPolicy. */
    @Input public abstract MapProperty<String, String> getSubscriptionAttributes();

    @TaskAction
    public void createTopicWithSqsEndpoint() {
        var settings = new SnsSqsSubscriptionProvisioner.Settings(topicName(), getTopicAttributes().get(),
            getQueueName().get(), getQueueAttributes().get(), getSubscriptionAttributes().get());
        var clients = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get());
        try (var sns = clients.sns(); var sqs = clients.sqs()) {
            String arn = new SnsSqsSubscriptionProvisioner().provision(sns, sqs, settings);
            getLogger().lifecycle("SNS topic ready: {} (SQS queue: {}, subscription: {})", settings.topicName(), settings.queueName(), arn);
        }
    }
}
