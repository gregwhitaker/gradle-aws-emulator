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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers Cognito User Pool tasks without starting Docker. */
public final class CognitoModule {
    private CognitoModule() {}

    /** Configures custom task types and registers inspection/deletion commands for the development runtime. */
    public static void load(Project project) {
        configure(project, CreateCognitoUserPoolTask.class, "Creates or reuses a Cognito User Pool.");
        configure(project, CreateCognitoUserPoolClientTask.class, "Creates or reuses a public Cognito app client.");
        configure(project, SeedCognitoUsersTask.class, "Seeds Cognito users and group memberships from JSON.");
        configure(project, ExportCognitoConfigurationTask.class, "Exports current Cognito IDs and local issuer/JWKS URLs.");
        configure(project, ListCognitoUserPoolsTask.class, "Lists Cognito User Pools.");
        configure(project, DeleteCognitoUserPoolTask.class, "Deletes a Cognito User Pool and its contents.");
        configure(project, DeleteCognitoUserPoolClientTask.class, "Deletes a Cognito app client.");
        project.getTasks().register("listCognitoUserPools", ListCognitoUserPoolsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteCognitoUserPool", DeleteCognitoUserPoolTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteCognitoUserPoolClient", DeleteCognitoUserPoolClientTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup("AWS Emulator - Cognito");
            task.setDescription(description);
        });
    }
}
