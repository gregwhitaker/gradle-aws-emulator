package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;

/** Lists the selected runtime's SES configuration sets. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ListSesConfigurationSetsTask extends AbstractAwsResourceTask {
    @TaskAction public void executeTask() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().configurationSets(client).forEach(name -> getLogger().lifecycle("{}", name));
        } catch (SdkException error) { throw SesOperations.failure("ListSesConfigurationSets", error); }
    }
}
