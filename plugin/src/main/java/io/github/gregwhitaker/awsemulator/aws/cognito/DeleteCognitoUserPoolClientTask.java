package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Deletes the selected app client. A missing pool or client is a no-op. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteCognitoUserPoolClientTask extends AbstractCognitoUserPoolClientTask {
    @TaskAction public void delete() {
        String pool = userPoolName();
        String name = clientName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            new CognitoOperations().deleteClient(client, pool, name);
            getLogger().lifecycle("Cognito app client absent: {}", name);
        }
    }
}
