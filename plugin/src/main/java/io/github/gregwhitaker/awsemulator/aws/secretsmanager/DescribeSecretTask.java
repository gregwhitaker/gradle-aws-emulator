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

/** Describes secret identity and deletion state without printing its value. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DescribeSecretTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getSecretName();
    @Option(option = "secretName", description = "SecretName") public void setSecretNameOption(String value) { getSecretName().set(value); }
    @TaskAction
    public void executeTask() {
        ServiceSupport.name(getSecretName().get(), SecretOperations.NAME, "secretName");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            var entry = client.describeSecret(software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest.builder().secretId(getSecretName().get()).build());
            getLogger().lifecycle("{}\t{}\tDeletion date: {}", entry.name(), entry.arn(), entry.deletedDate());
        }
    }
}
