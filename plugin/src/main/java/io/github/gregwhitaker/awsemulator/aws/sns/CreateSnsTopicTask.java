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
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates or reuses a standard or FIFO topic, verifying explicitly requested attributes. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSnsTopicTask extends AbstractSnsTopicTask {
    public CreateSnsTopicTask() { getAttributes().convention(Map.of()); }

    /** AWS SNS attribute names and string values. FIFO topics require FifoTopic=true. */
    @Input public abstract MapProperty<String, String> getAttributes();

    @TaskAction
    public void createTopic() {
        String name = topicName();
        var attributes = getAttributes().get();
        SnsTopicOperations.validate(name, attributes);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            String arn = new SnsTopicOperations().provision(client, name, attributes);
            getLogger().lifecycle("SNS topic ready: {} ({})", name, arn);
        }
    }
}
