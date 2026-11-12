package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

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

/** Creates or reuses a Secrets Manager value; changing an existing value requires overwrite=true. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutSecretTask extends AbstractAwsResourceTask {
    public PutSecretTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getSecretName();
    @Input public abstract Property<String> getValue();
    @Input @Optional public abstract Property<String> getKeyId();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        var entry = new SecretOperations.Secret(getSecretName().get(), getValue().get(), getKeyId().getOrNull());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            new SecretOperations().put(client, entry, getOverwrite().get());
            getLogger().lifecycle("Secrets Manager resource ready: {}", entry.name());
        }
    }
}
