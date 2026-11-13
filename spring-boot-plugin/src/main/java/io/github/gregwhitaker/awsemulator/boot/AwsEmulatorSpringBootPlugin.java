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

package io.github.gregwhitaker.awsemulator.boot;

import io.github.gregwhitaker.awsemulator.AwsEmulatorPlugin;
import io.github.gregwhitaker.awsemulator.boot.internal.SpringBootTasks;
import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorExtension;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionAware;
import java.util.List;

/** Adds local run and debug tasks when the application's Spring Boot and Java plugins are present. */
public final class AwsEmulatorSpringBootPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(AwsEmulatorPlugin.class);
        AwsEmulatorExtension emulator = project.getExtensions().getByType(AwsEmulatorExtension.class);
        SpringBootEmulatorExtension extension = ((ExtensionAware) emulator).getExtensions()
            .create("springBoot", SpringBootEmulatorExtension.class);
        // Preserve existing builds while exposing the companion settings under awsEmulator.
        project.getExtensions().add("springBootEmulator", extension);
        extension.getRuntimeName().convention("default");
        extension.getMode().convention(EmulatorMode.DEVELOPMENT);
        extension.getProfiles().convention(List.of("local"));
        extension.getJvmArgs().convention(List.of());
        extension.getSetupTasks().convention(List.of());
        extension.getResourceSets().convention(List.of());
        extension.getAutoCreateResources().convention(true);
        extension.getExcludedSetupTasks().convention(List.of());
        extension.getDebugPort().convention(5005);
        extension.getDebugSuspend().convention(true);

        project.getPluginManager().withPlugin("org.springframework.boot", boot ->
            project.getPluginManager().withPlugin("java", java -> SpringBootTasks.register(project, extension)));
    }
}
