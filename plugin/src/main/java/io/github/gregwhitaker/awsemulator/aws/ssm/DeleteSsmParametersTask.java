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

/** Removes selected Parameter Store resources; missing resources are safe to repeat. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteSsmParametersTask extends AbstractAwsResourceTask {

    @Input public abstract ListProperty<String> getNames();
    @Option(option = "names", description = "Names") public void setNamesOption(java.util.List<String> value) { getNames().set(value); }
    @TaskAction
    public void executeTask() {
        var names = ServiceSupport.names(getNames().get(), SsmOperations.NAME, "names");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).ssm()) {
            for (String name : names) {
                new SsmOperations().delete(client, name);
                getLogger().lifecycle("Deletion requested: {}", name);
            }
        }
    }
}
