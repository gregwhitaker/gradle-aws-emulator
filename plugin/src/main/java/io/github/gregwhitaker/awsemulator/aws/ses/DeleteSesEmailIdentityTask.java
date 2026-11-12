package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

/** Deletes one identity; an absent identity is already deleted. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteSesEmailIdentityTask extends AbstractSesEmailIdentityTask {
    @TaskAction public void executeTask() {
        String identity = emailIdentity();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().deleteIdentity(client, identity);
        } catch (SdkException error) { throw SesOperations.failure("DeleteSesEmailIdentity", error); }
    }
}
