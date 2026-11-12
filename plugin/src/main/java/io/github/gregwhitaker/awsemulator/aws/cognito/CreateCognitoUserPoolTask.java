package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates a pool with Cognito defaults, or reuses an existing uniquely named pool unchanged. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class CreateCognitoUserPoolTask extends AbstractCognitoUserPoolTask {
    @TaskAction public void create() {
        String name = userPoolName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            String id = new CognitoOperations().createPool(client, name);
            getLogger().lifecycle("Cognito user pool ready: {} ({})", name, id);
        }
    }
}
