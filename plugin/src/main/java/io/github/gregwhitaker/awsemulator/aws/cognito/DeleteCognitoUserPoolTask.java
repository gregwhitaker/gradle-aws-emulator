package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Deletes the selected pool and its users, groups, and app clients. Missing pools are a no-op. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteCognitoUserPoolTask extends AbstractCognitoUserPoolTask {
    @TaskAction public void delete() {
        String name = userPoolName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            new CognitoOperations().deletePool(client, name);
            getLogger().lifecycle("Cognito user pool absent: {}", name);
        }
    }
}
