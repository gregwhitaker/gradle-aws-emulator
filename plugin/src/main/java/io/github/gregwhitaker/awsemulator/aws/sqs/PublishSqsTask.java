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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.io.File;

/** Publishes each nonempty UTF-8 line from files, directories, or tar.gz/tgz archives to every selected queue. */
@DisableCachingByDefault(because = "Publishes messages to live emulator queues")
public abstract class PublishSqsTask extends AbstractSqsQueuesTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getMessages();

    @Input @Optional
    public abstract Property<String> getMessageGroupId();

    @Option(option = "message", description = "Message file, directory, or tar.gz/tgz archive")
    public void setMessage(String path) { getMessages().setFrom(path); }

    @Option(option = "messageGroupId", description = "Message group ID, required when publishing to FIFO queues")
    public void setMessageGroupIdOption(String value) { getMessageGroupId().set(value); }

    @TaskAction
    public void publish() {
        var names = queueNames();
        String group = getMessageGroupId().getOrNull();
        if (names.stream().anyMatch(name -> name.endsWith(".fifo")) && (group == null || group.isBlank())) {
            throw new GradleException("messageGroupId is required when publishing to FIFO queues.");
        }
        if (group != null && (group.isEmpty() || group.length() > 128 || group.chars().anyMatch(c -> c < 33 || c > 126))) {
            throw new GradleException("messageGroupId must contain 1–128 alphanumeric or punctuation characters.");
        }
        var files = getMessages().getFiles().stream().map(File::toPath).sorted().toList();
        if (files.isEmpty()) throw new GradleException("messages must contain at least one file, directory, or archive.");
        var reader = new SqsMessageReader();
        // Validate files and archives before starting Docker or publishing any messages.
        reader.read(files, ignored -> {});
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            long count = new SqsMessagePublisher().publish(client, names, files, group);
            getLogger().lifecycle("Published {} SQS {} to {} {}.", count, count == 1 ? "message" : "messages",
                names.size(), names.size() == 1 ? "queue" : "queues");
        }
    }
}
