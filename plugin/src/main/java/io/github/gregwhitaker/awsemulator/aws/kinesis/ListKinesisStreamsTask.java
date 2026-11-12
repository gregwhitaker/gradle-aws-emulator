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

/** Operates on resources in the selected AWS Emulator instance. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ListKinesisStreamsTask extends AbstractKinesisTask {


    @TaskAction
    public void executeTask() {
        runKinesis("list", "", 0, 0, List.of(), List.of());
    }
}
