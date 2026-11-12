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
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.Map;

/** Creates or reuses a standard or FIFO queue in AWS Emulator. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSqsQueueTask extends AbstractAwsResourceTask {
    public CreateSqsQueueTask() { getAttributes().convention(Map.of()); }

    @Input
    public abstract Property<String> getQueueName();

    /** AWS SQS attribute names and string values; defaults to the service defaults. */
    @Input
    public abstract MapProperty<String, String> getAttributes();

    @TaskAction
    public void createQueue() {
        String name = getQueueName().get();
        Map<String, String> attributes = getAttributes().get();
        SqsQueueProvisioner.validate(name, attributes);
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).sqs()) {
            String url = new SqsQueueProvisioner().provision(client, name, attributes);
            getLogger().lifecycle("SQS queue ready: {} ({})", name, url);
        }
    }
}
