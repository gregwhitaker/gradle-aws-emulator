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

package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates a queue and its DLQ, applying the source queue's requested attributes and redrive policy. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSqsQueueWithDlqTask extends AbstractAwsResourceTask {
    public CreateSqsQueueWithDlqTask() {
        getQueueAttributes().convention(Map.of());
        getDeadletterQueueAttributes().convention(Map.of());
        getDeadletterQueueName().convention(getQueueName().map(SqsDeadLetterQueueProvisioner::defaultName));
        getMaxReceiveCount().convention(1);
    }

    @Input public abstract Property<String> getQueueName();
    @Input public abstract MapProperty<String, String> getQueueAttributes();
    @Input public abstract Property<String> getDeadletterQueueName();
    @Input public abstract MapProperty<String, String> getDeadletterQueueAttributes();
    @Input public abstract Property<Integer> getMaxReceiveCount();

    @TaskAction
    public void createQueueWithDlq() {
        var settings = new SqsDeadLetterQueueProvisioner.Settings(getQueueName().get(), getQueueAttributes().get(),
            getDeadletterQueueName().get(), getDeadletterQueueAttributes().get(), getMaxReceiveCount().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            new SqsDeadLetterQueueProvisioner().provision(client, settings);
        }
        getLogger().lifecycle("SQS queue ready: {} (DLQ: {})", settings.name(), settings.dlqName());
    }
}
