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

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
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

/** Creates or reuses a provisioned Kinesis stream and waits for ACTIVE. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateKinesisStreamTask extends AbstractKinesisTask {
    public CreateKinesisStreamTask() { getShardCount().convention(1); getWaitTimeoutSeconds().convention(60); }
    @Input public abstract Property<String> getStreamName();
    @Option(option = "streamName", description = "StreamName") public void setStreamNameOption(String value) { getStreamName().set(value); }
    @Input public abstract Property<Integer> getShardCount();
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();
    @TaskAction
    public void executeTask() {
        String name = ServiceSupport.name(getStreamName().get(), KinesisOperations.NAME, "streamName");
        if (getShardCount().get() < 1) throw new GradleException("shardCount must be positive.");
        ServiceSupport.positive(getWaitTimeoutSeconds().get());
        runKinesis("create", name, getShardCount().get(), getWaitTimeoutSeconds().get(), List.of(), List.of());
    }
}
