package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists all Cognito User Pool names and IDs, following pagination. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ListCognitoUserPoolsTask extends AbstractAwsResourceTask {
    @TaskAction public void list() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            getLogger().lifecycle("UserPoolName\tUserPoolId");
            for (var pool : new CognitoOperations().pools(client)) getLogger().lifecycle("{}\t{}", pool.name(), pool.id());
        }
    }
}
