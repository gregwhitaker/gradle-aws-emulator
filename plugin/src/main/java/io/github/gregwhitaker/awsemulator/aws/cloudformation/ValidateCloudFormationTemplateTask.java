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

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Checks the local JSON/YAML template and pinned provisioning catalogue without starting or contacting the emulator. */
@DisableCachingByDefault(because = "Validation has no output artifact")
public abstract class ValidateCloudFormationTemplateTask extends AbstractAwsEmulatorTask {
    /** Local UTF-8 JSON or YAML template; no remote templates or transformations are evaluated. */
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getTemplateFile();

    @Option(option = "templateFile", description = "Local JSON/YAML CloudFormation template")
    public void setTemplateFileOption(String path) { getTemplateFile().set(getProject().getLayout().getProjectDirectory().file(path)); }

    @TaskAction public void validateTemplate() {
        CloudFormationTemplate.read(getTemplateFile().get().getAsFile().toPath(),
            getRuntime().get().getParameters().getEmulatorVersion().get());
        getLogger().lifecycle("CloudFormation template passed provisioning checks for AWS Emulator {}.", CloudFormationTemplate.EMULATOR_VERSION);
    }
}
