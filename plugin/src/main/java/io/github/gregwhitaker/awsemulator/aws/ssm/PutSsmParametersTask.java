package io.github.gregwhitaker.awsemulator.aws.ssm;

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

/** Loads Parameter Store fixtures from a JSON array; validates every entry before provisioning. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutSsmParametersTask extends AbstractAwsResourceTask {
    public PutSsmParametersTask() { getOverwrite().convention(false); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFixtures();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        var entries = JsonFixtures.read(getFixtures().get().getAsFile().toPath(), java.util.Set.of("name", "value", "keyId", "type")).stream()
            .map(row -> new SsmOperations.Parameter(JsonFixtures.string(row, "name", null), JsonFixtures.string(row, "value", null), JsonFixtures.string(row, "type", "String"), JsonFixtures.optional(row, "keyId"))).toList();
        ServiceSupport.names(entries.stream().map(SsmOperations.Parameter::name).toList(), SsmOperations.NAME, "fixture names");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).ssm()) {
            int completed = 0;
            for (var entry : entries) {
                try { new SsmOperations().put(client, entry, getOverwrite().get()); completed++; }
                catch (RuntimeException e) { throw new GradleException("Provisioning stopped at '" + entry.name() + "' after " + completed + " completed entries; rerun after resolving the failure.", e); }
            }
            getLogger().lifecycle("Prepared {} Parameter Store resources.", completed);
        }
    }
}
