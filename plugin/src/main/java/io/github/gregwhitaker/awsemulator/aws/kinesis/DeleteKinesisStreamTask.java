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

/** Deletes the selected Kinesis stream and waits for its disappearance. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteKinesisStreamTask extends AbstractKinesisTask {
    public DeleteKinesisStreamTask() { getWaitTimeoutSeconds().convention(60); }
    @Input public abstract Property<String> getStreamName();
    @Option(option = "streamName", description = "StreamName") public void setStreamNameOption(String value) { getStreamName().set(value); }
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();
    @TaskAction
    public void executeTask() {
        String name = ServiceSupport.name(getStreamName().get(), KinesisOperations.NAME, "streamName");
        ServiceSupport.positive(getWaitTimeoutSeconds().get());
        runKinesis("delete", name, 0, getWaitTimeoutSeconds().get(), List.of(), List.of());
    }
}
