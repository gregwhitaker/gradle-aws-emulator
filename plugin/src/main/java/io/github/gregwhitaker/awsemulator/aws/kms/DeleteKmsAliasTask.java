package io.github.gregwhitaker.awsemulator.aws.kms;

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
public abstract class DeleteKmsAliasTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getAliasName();
    @Option(option = "aliasName", description = "AliasName") public void setAliasNameOption(String value) { getAliasName().set(value); }
    @TaskAction
    public void executeTask() {
        String alias = KmsOperations.alias(getAliasName().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            try { client.deleteAlias(software.amazon.awssdk.services.kms.model.DeleteAliasRequest.builder().aliasName(alias).build()); }
            catch (software.amazon.awssdk.services.kms.model.NotFoundException ignored) { }
        }
    }
}
