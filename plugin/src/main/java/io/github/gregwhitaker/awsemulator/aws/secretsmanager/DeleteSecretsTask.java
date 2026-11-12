package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

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

/** Removes selected Secrets Manager resources; missing resources are safe to repeat. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteSecretsTask extends AbstractAwsResourceTask {
    public DeleteSecretsTask() { getForce().convention(false); getRecoveryWindowInDays().convention(30); }
    @Input public abstract ListProperty<String> getNames();
    @Option(option = "names", description = "Names") public void setNamesOption(java.util.List<String> value) { getNames().set(value); }
    @Input public abstract Property<Boolean> getForce();
    @Input public abstract Property<Integer> getRecoveryWindowInDays();
    @Option(option = "force", description = "Force") public void setForceOption(boolean value) { getForce().set(value); }
    @TaskAction
    public void executeTask() {
        var names = ServiceSupport.names(getNames().get(), SecretOperations.NAME, "names");
        if (getRecoveryWindowInDays().get() < 7 || getRecoveryWindowInDays().get() > 30) throw new GradleException("recoveryWindowInDays must be between 7 and 30.");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            for (String name : names) {
                new SecretOperations().delete(client, name, getForce().get(), getRecoveryWindowInDays().get());
                getLogger().lifecycle("Deletion requested: {}", name);
            }
        }
    }
}
