package io.github.gregwhitaker.awsemulator.aws.ssm;

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

/** Creates or reuses a Parameter Store value; changing an existing value requires overwrite=true. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PutSsmParameterTask extends AbstractAwsResourceTask {
    public PutSsmParameterTask() { getOverwrite().convention(false); getType().convention("String"); }
    @Input public abstract Property<String> getParameterName();
    @Input public abstract Property<String> getValue();
    @Input @Optional public abstract Property<String> getKeyId();
    @Input public abstract Property<Boolean> getOverwrite();
    @Input public abstract Property<String> getType();
    @TaskAction
    public void executeTask() {
        var entry = new SsmOperations.Parameter(getParameterName().get(), getValue().get(), getType().get(), getKeyId().getOrNull());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).ssm()) {
            new SsmOperations().put(client, entry, getOverwrite().get());
            getLogger().lifecycle("Parameter Store resource ready: {}", entry.name());
        }
    }
}
