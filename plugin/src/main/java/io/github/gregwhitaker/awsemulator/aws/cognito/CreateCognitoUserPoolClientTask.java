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
