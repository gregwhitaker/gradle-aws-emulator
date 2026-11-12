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
public abstract class ListLogGroupsTask extends AbstractAwsResourceTask {


    @TaskAction
    public void executeTask() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudWatchLogs()) {
            new CloudWatchLogsOperations().groups(client).forEach(n -> getLogger().lifecycle("{}", n));
        }
    }
}
