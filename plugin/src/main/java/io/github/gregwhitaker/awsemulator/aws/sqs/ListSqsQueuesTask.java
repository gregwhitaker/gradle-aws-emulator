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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Lists queue names, URLs, and approximate available message counts. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListSqsQueuesTask extends AbstractAwsResourceTask {
    @Input @Optional
    public abstract Property<String> getPrefix();

    @Option(option = "prefix", description = "Only list queues whose names start with this prefix")
    public void setPrefixOption(String prefix) { getPrefix().set(prefix); }

    @TaskAction
    public void listQueues() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            var queues = new SqsQueueOperations().list(client, getPrefix().getOrNull());
            getLogger().lifecycle("QueueName\tQueueUrl\tApproximateNumberOfMessages");
            queues.forEach(queue -> getLogger().lifecycle("{}\t{}\t{}", queue.name(), queue.url(), queue.messages()));
            if (queues.isEmpty()) getLogger().lifecycle("No Queues Found!");
        }
    }
}
