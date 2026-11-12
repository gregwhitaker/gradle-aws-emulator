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

/** Lists Secrets Manager resource names without printing values. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ListSecretsTask extends AbstractAwsResourceTask {


    @TaskAction
    public void executeTask() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            new SecretOperations().list(client).forEach(entry -> getLogger().lifecycle("{}", entry.name()));
        }
    }
}
