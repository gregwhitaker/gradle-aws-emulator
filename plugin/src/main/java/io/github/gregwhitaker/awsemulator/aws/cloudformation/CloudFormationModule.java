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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers guarded CloudFormation tasks without starting Docker or accessing template files. */
public final class CloudFormationModule {
    private CloudFormationModule() {}

    public static void load(Project project) {
        configure(project, ValidateCloudFormationTemplateTask.class, "Checks local CloudFormation resource provisioning support without Docker.");
        configure(project, DeployCloudFormationStackTask.class, "Validates, deploys, and waits for a CloudFormation stack.");
        configure(project, DeleteCloudFormationStackTask.class, "Deletes a CloudFormation stack and waits for removal.");
        configure(project, ListCloudFormationStacksTask.class, "Lists CloudFormation stacks and statuses.");
        project.getTasks().register("validateCloudFormationTemplate", ValidateCloudFormationTemplateTask.class);
        development(project, "deleteCloudFormationStack", DeleteCloudFormationStackTask.class);
        development(project, "listCloudFormationStacks", ListCloudFormationStacksTask.class);
        project.getTasks().withType(DeployCloudFormationStackTask.class).configureEach(task -> task.getOutputsFile()
            .convention(project.getLayout().getBuildDirectory().file("aws-emulator/cloudformation/" + task.getName() + "/outputs.json")));
    }

    private static <T extends AbstractAwsEmulatorTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup("AWS Emulator - CloudFormation");
            task.setDescription(description);
        });
    }

    private static <T extends AbstractAwsResourceTask> void development(Project project, String name, Class<T> type) {
        project.getTasks().register(name, type, task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
}
