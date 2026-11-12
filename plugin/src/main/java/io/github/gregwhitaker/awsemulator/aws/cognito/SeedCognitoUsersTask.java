/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
