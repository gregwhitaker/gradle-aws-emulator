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

/** Writes matching events to JSON, optionally waiting for a minimum count with a bounded deadline. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class FilterLogEventsTask extends AbstractAwsResourceTask {
    public FilterLogEventsTask() { getMaxEvents().convention(10000); getFilterPattern().convention(""); getMinimumEvents().convention(0); getWaitTimeoutSeconds().convention(30); }
    @Input public abstract Property<String> getLogGroupName();
    @Option(option = "logGroupName", description = "LogGroupName") public void setLogGroupNameOption(String value) { getLogGroupName().set(value); }
    @Input @Optional public abstract Property<Long> getStartTime();
    @Input public abstract Property<Integer> getMaxEvents();
    @OutputFile public abstract RegularFileProperty getOutputFile();
    @Input public abstract Property<String> getFilterPattern();
    @Input public abstract Property<Integer> getMinimumEvents();
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();
    @TaskAction
    public void executeTask() {
        String group = CloudWatchLogsOperations.group(getLogGroupName().get());
        int max = getMaxEvents().get();
        if (max < 1 || max > 1000000) throw new GradleException("maxEvents must be between 1 and 1000000.");
        Long start = getStartTime().getOrNull();
        if (start != null && start < 0) throw new GradleException("startTime must be nonnegative epoch milliseconds.");
        int minimum = getMinimumEvents().get();
        if (minimum < 0 || minimum > max) throw new GradleException("minimumEvents must be between zero and maxEvents.");
        ServiceSupport.positive(getWaitTimeoutSeconds().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudWatchLogs()) {
            var ops = new CloudWatchLogsOperations();
            var result = new java.util.concurrent.atomic.AtomicReference<java.util.List<java.util.Map<String, Object>>>();
            ServiceSupport.await("waiting for matching log events", getWaitTimeoutSeconds().get(), () -> {
                result.set(ops.filter(client, group, getFilterPattern().get(), start, max));
                return result.get().size() >= minimum;
            });
            ops.output(getOutputFile().get().getAsFile().toPath(), result.get());
        }
    }
}
