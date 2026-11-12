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

package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

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

/** Creates a log stream inside an existing log group. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateLogStreamTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getLogGroupName();
    @Option(option = "logGroupName", description = "LogGroupName") public void setLogGroupNameOption(String value) { getLogGroupName().set(value); }
    @Input public abstract Property<String> getLogStreamName();
    @Option(option = "logStreamName", description = "LogStreamName") public void setLogStreamNameOption(String value) { getLogStreamName().set(value); }
    @TaskAction
    public void executeTask() {
        String group = CloudWatchLogsOperations.group(getLogGroupName().get());
        String stream = CloudWatchLogsOperations.stream(getLogStreamName().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudWatchLogs()) {
            new CloudWatchLogsOperations().createStream(client, group, stream);
        }
    }
}
