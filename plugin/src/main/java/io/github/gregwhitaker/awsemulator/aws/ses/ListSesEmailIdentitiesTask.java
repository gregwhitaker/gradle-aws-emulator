package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;

/** Lists identity names and verification status without email content. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ListSesEmailIdentitiesTask extends AbstractAwsResourceTask {
    @TaskAction public void executeTask() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().identities(client).forEach(identity -> getLogger().lifecycle("{}\t{}",
                identity.identityName(), identity.verificationStatusAsString()));
        } catch (SdkException error) { throw SesOperations.failure("ListSesEmailIdentities", error); }
    }
}
