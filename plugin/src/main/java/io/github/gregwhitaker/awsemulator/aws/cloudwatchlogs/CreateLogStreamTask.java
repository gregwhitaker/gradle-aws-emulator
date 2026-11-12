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
