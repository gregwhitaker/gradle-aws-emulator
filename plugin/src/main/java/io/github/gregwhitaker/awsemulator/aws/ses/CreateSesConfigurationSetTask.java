package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;

/** Creates a configuration set or reuses it without changing existing options. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class CreateSesConfigurationSetTask extends AbstractSesConfigurationSetTask {
    @TaskAction public void executeTask() {
        String name = configurationSetName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().createConfigurationSet(client, name);
            getLogger().lifecycle("SES configuration set ready: {}", name);
        } catch (SdkException error) { throw SesOperations.failure("CreateSesConfigurationSet", error); }
    }
}
