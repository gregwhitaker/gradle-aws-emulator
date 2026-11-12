package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates a public (no secret) app client, or reuses one with matching explicit auth flows. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class CreateCognitoUserPoolClientTask extends AbstractCognitoUserPoolClientTask {
    public CreateCognitoUserPoolClientTask() { getExplicitAuthFlows().convention(CognitoOperations.DEFAULT_AUTH_FLOWS); }

    /** Password, admin password, and refresh flows by default; only those three ALLOW_* values are supported. */
    @Input public abstract ListProperty<String> getExplicitAuthFlows();

    @TaskAction public void create() {
        String pool = userPoolName();
        String name = clientName();
        var flows = CognitoOperations.authFlows(getExplicitAuthFlows().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            var operations = new CognitoOperations();
            String id = operations.createClient(client, operations.requirePool(client, pool), name, flows);
            getLogger().lifecycle("Cognito app client ready: {} ({})", name, id);
        }
    }
}
