package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

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

/** Operates on resources in the selected AWS Emulator instance. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteLogGroupTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getLogGroupName();
    @Option(option = "logGroupName", description = "LogGroupName") public void setLogGroupNameOption(String value) { getLogGroupName().set(value); }
    @TaskAction
    public void executeTask() {
        String group = CloudWatchLogsOperations.group(getLogGroupName().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudWatchLogs()) {
            try { client.deleteLogGroup(software.amazon.awssdk.services.cloudwatchlogs.model.DeleteLogGroupRequest.builder().logGroupName(group).build()); }
            catch (software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException ignored) { }
        }
    }
}
