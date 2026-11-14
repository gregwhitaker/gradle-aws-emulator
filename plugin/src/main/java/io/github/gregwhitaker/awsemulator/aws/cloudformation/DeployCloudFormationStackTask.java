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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Validates locally, creates or updates a stack, waits for success, and exports its outputs. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Provisions live emulator stacks")
public abstract class DeployCloudFormationStackTask extends AbstractCloudFormationStackTask {
    public DeployCloudFormationStackTask() {
        getParameters().convention(Map.of());
        getTags().convention(Map.of());
        getCapabilities().convention(List.of());
    }

    /** Local UTF-8 JSON/YAML template. Every declaration is checked, even when conditional. */
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getTemplateFile();
    /** Parameter values sent on both create and update. Omitted parameters use template defaults. */
    @Input public abstract MapProperty<String, String> getParameters();
    /** Complete set of stack tags sent on create and update. */
    @Input public abstract MapProperty<String, String> getTags();
    /** Explicit IAM acknowledgements: CAPABILITY_IAM and/or CAPABILITY_NAMED_IAM. */
    @Input public abstract ListProperty<String> getCapabilities();
    /** JSON object mapping output keys to values; defaults under build/aws-emulator/cloudformation/taskName/. */
    @OutputFile public abstract RegularFileProperty getOutputsFile();

    @Option(option = "templateFile", description = "Local JSON/YAML CloudFormation template")
    public void setTemplateFileOption(String path) { getTemplateFile().set(getProject().getLayout().getProjectDirectory().file(path)); }

    @TaskAction public void deploy() {
        String name = stackName();
        var source = getTemplateFile().get().getAsFile().toPath();
        var output = getOutputsFile().get().getAsFile().toPath();
        try {
            if (source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || Files.exists(output) && Files.isSameFile(source, output)) {
                throw new GradleException("CloudFormation templateFile and outputsFile must differ.");
            }
        } catch (java.io.IOException error) {
            throw new GradleException("Cannot check CloudFormation input and output paths.", error);
        }
        String template = CloudFormationTemplate.read(source,
            getRuntime().get().getParameters().getEmulatorVersion().get());
        var parameters = CloudFormationOperations.parameters(getParameters().get());
        var tags = CloudFormationOperations.tags(getTags().get());
        var capabilities = CloudFormationOperations.capabilities(getCapabilities().get());
        // All local validation precedes connection(): rejected templates never start Docker.
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudFormation()) {
            var stack = new CloudFormationOperations().deploy(client, name, template, parameters, tags, capabilities,
                getWaitTimeoutSeconds().get());
            CloudFormationOperations.writeOutputs(stack, output);
            getLogger().lifecycle("CloudFormation stack complete: {} ({})", name, stack.stackStatusAsString());
        }
    }
}
