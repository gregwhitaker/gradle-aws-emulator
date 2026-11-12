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

package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Publishes UTF-8 JSON record fixtures, detecting partial failures without replaying successful records. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PublishKinesisRecordsTask extends AbstractKinesisTask {

    @Input public abstract Property<String> getStreamName();
    @Option(option = "streamName", description = "StreamName") public void setStreamNameOption(String value) { getStreamName().set(value); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFixtures();
    @TaskAction
    public void executeTask() {
        String name = ServiceSupport.name(getStreamName().get(), KinesisOperations.NAME, "streamName");
        var records = JsonFixtures.read(getFixtures().get().getAsFile().toPath(), java.util.Set.of("partitionKey", "data")).stream().map(row -> {
            String key = ServiceSupport.text(JsonFixtures.string(row, "partitionKey", null), "partitionKey");
            String data = JsonFixtures.string(row, "data", null);
            if (key.length() > 256) throw new GradleException("partitionKey exceeds 256 characters.");
            if ((key + data).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) throw new GradleException("Kinesis fixture exceeds the supported 1 MiB record limit.");
            return software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry.builder().partitionKey(key)
                .data(software.amazon.awssdk.core.SdkBytes.fromUtf8String(data)).build();
        }).toList();
        runKinesis("publish", name, 0, 0, records.stream().map(r -> r.partitionKey()).toList(), records.stream().map(r -> r.data().asUtf8String()).toList());
    }
}
