package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Seeds confirmed users and additive group memberships from JSON, suppressing invitation delivery. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class SeedCognitoUsersTask extends AbstractCognitoUserPoolTask {
    public SeedCognitoUsersTask() { getOverwrite().convention(false); }

    /** JSON array of username/password entries with optional attributes and groups. Use only test credentials. */
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFixtures();

    /** Replace supplied attributes and reset permanent passwords for existing enabled users. Defaults to false. */
    @Input public abstract Property<Boolean> getOverwrite();

    @TaskAction public void seed() {
        String pool = userPoolName();
        var users = CognitoUserFixtures.read(getFixtures().get().getAsFile().toPath());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cognitoIdentityProvider()) {
            String poolId = new CognitoOperations().requirePool(client, pool);
            int completed = 0;
            for (var user : users) {
                try { new CognitoUserFixtures().seed(client, poolId, user, getOverwrite().get()); completed++; }
                catch (GradleException e) {
                    throw new GradleException("Cognito fixture entry " + (completed + 1) + " failed after " + completed
                        + " completed entries; resolve the failure and rerun. " + e.getMessage());
                }
            }
            getLogger().lifecycle("Prepared {} Cognito users in '{}'.", completed, pool);
        }
    }
}
