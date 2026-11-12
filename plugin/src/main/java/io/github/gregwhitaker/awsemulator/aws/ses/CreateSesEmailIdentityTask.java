package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/** Creates or reuses an identity, then waits for the emulator to verify it. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class CreateSesEmailIdentityTask extends AbstractSesEmailIdentityTask {
    public CreateSesEmailIdentityTask() { getWaitTimeoutSeconds().convention(30); }

    /** Maximum time to wait for local identity verification; defaults to 30 seconds. */
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();
    @TaskAction public void executeTask() {
        String identity = emailIdentity();
        int timeout = getWaitTimeoutSeconds().get();
        ServiceSupport.positive(timeout);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().createIdentity(client, identity, timeout);
            getLogger().lifecycle("SES identity ready: {}", identity);
        } catch (SdkException error) { throw SesOperations.failure("CreateSesEmailIdentity", error); }
    }
}
