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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Publishes one UTF-8 message (up to 256 KiB) to an existing standard or FIFO topic. */
@DisableCachingByDefault(because = "Publishes messages to live emulator topics")
public abstract class PublishSnsTask extends AbstractSnsTopicTask {
    @Input public abstract Property<String> getMessage();
    @Input @Optional public abstract Property<String> getSubject();
    @Input @Optional public abstract Property<String> getMessageGroupId();
    @Input @Optional public abstract Property<String> getMessageDeduplicationId();

    @Option(option = "message", description = "Message text to publish")
    public void setMessageOption(String value) { getMessage().set(value); }

    @Option(option = "subject", description = "Optional message subject")
    public void setSubjectOption(String value) { getSubject().set(value); }

    @Option(option = "messageGroupId", description = "Message group ID, required for FIFO topics")
    public void setMessageGroupIdOption(String value) { getMessageGroupId().set(value); }

    @Option(option = "messageDeduplicationId", description = "FIFO deduplication ID; defaults to a unique ID for this publish")
    public void setMessageDeduplicationIdOption(String value) { getMessageDeduplicationId().set(value); }

    @TaskAction
    public void publish() {
        String name = topicName();
        String message = getMessage().get();
        String subject = getSubject().getOrNull();
        String group = getMessageGroupId().getOrNull();
        String deduplication = getMessageDeduplicationId().getOrNull();
        SnsMessagePublisher.validate(name, message, subject, group, deduplication);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            String id = new SnsMessagePublisher().publish(client, name, message, subject, group, deduplication);
            getLogger().lifecycle("Published SNS message to {} (message ID: {}).", name, id);
        }
    }
}
