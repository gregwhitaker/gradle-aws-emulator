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
public abstract class CreateKmsAliasTask extends AbstractAwsResourceTask {
    public CreateKmsAliasTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getAliasName();
    @Option(option = "aliasName", description = "AliasName") public void setAliasNameOption(String value) { getAliasName().set(value); }
    @Input public abstract Property<String> getKeyId();
    @Option(option = "keyId", description = "KeyId") public void setKeyIdOption(String value) { getKeyId().set(value); }
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String alias = KmsOperations.alias(getAliasName().get());
        String key = ServiceSupport.text(getKeyId().get(), "keyId");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            new KmsOperations().alias(client, alias, key, getOverwrite().get());
        }
    }
}
