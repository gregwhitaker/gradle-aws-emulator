package io.github.gregwhitaker.awsemulator.aws.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.ResourceFiles;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.util.Map;

/** Exports current IDs and local issuer/JWKS URLs as JSON. Contains no passwords, tokens, or client secrets. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ExportCognitoConfigurationTask extends AbstractCognitoUserPoolClientTask {
    /** Destination read by a consuming application at execution time; use different files for different runtimes. */
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void export() {
        String pool = userPoolName();
        String name = clientName();
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            var operations = new CognitoOperations();
            String poolId = operations.requirePool(client, pool);
            String clientId = operations.requireClient(client, poolId, name);
            String issuer = connection.endpoint() + "/" + poolId;
            byte[] json;
            try {
                json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "userPoolId", poolId, "clientId", clientId, "region", connection.region(),
                    "endpoint", connection.endpoint(), "issuer", issuer, "jwksUri", issuer + "/.well-known/jwks.json"));
            } catch (java.io.IOException e) { throw new GradleException("Cannot serialize Cognito configuration.", e); }
            ResourceFiles.write(getOutputFile().get().getAsFile().toPath(), json);
            getLogger().lifecycle("Exported Cognito configuration for pool '{}' and client '{}'.", pool, name);
        }
    }
}
