package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

/** Deletes a configuration set and its destinations; absence is a no-op. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteSesConfigurationSetTask extends AbstractSesConfigurationSetTask {
    @TaskAction public void executeTask() {
        String name = configurationSetName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().deleteConfigurationSet(client, name);
        } catch (SdkException error) { throw SesOperations.failure("DeleteSesConfigurationSet", error); }
    }
}
