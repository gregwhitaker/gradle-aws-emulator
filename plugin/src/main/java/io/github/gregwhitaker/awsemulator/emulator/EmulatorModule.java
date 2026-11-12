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

package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;

/** Registers the emulator extension, shared runtime, and lifecycle task group. */
public final class EmulatorModule {
    private static final String GROUP_NAME = "AWS Emulator";
    private static final String PREPARE_TASK_NAME = "prepareAwsEmulator";
    private static final String START_TASK_NAME = "startAwsEmulator";
    private static final String STOP_TASK_NAME = "stopAwsEmulator";
    private static final String RESTART_TASK_NAME = "restartAwsEmulator";
    private static final String STATUS_TASK_NAME = "statusAwsEmulator";
    private static final String LOGS_TASK_NAME = "logsAwsEmulator";

    private EmulatorModule() {}

    public static void load(Project project) {
        AwsEmulatorExtension extension = registerExtension(project);
        Provider<AwsEmulatorService> service = registerService(project, extension);
        extension.attach(project, service);
        project.getTasks().withType(AbstractAwsEmulatorTask.class).configureEach(task -> {
            task.getRuntime().convention(extension.runtime());
            extension.usesRuntime(task);
        });
        register(project, PREPARE_TASK_NAME, PrepareAwsEmulatorTask.class, "Builds or locates the AWS Emulator Docker image.");
        register(project, START_TASK_NAME, StartAwsEmulatorTask.class, "Starts the project's persistent development emulator.");
        register(project, STOP_TASK_NAME, StopAwsEmulatorTask.class, "Stops the project's persistent development emulator.");
        register(project, RESTART_TASK_NAME, RestartAwsEmulatorTask.class, "Restarts the development emulator with empty state.");
        register(project, STATUS_TASK_NAME, StatusAwsEmulatorTask.class, "Reports the development emulator's status and endpoint.");
        register(project, LOGS_TASK_NAME, LogsAwsEmulatorTask.class, "Displays recent development emulator logs.");
        register(project, "doctorAwsEmulator", DoctorAwsEmulatorTask.class, "Diagnoses Docker, images, ports, and project-owned emulator connections.");
        register(project, "cleanupAwsEmulator", CleanupAwsEmulatorTask.class, "Archives logs and removes stale project-owned managed containers.");
        register(project, "exportAwsEmulatorConnection", ExportAwsEmulatorConnectionTask.class, "Exports development connection settings for POSIX shells and PowerShell.");
        ScenarioModule.load(project, extension);
    }

    private static AwsEmulatorExtension registerExtension(Project project) {
        AwsEmulatorExtension extension = project.getExtensions().create("awsEmulator", AwsEmulatorExtension.class);
        extension.getEmulatorVersion().convention("1.0.322");
        extension.getPlatform().convention("auto");
        extension.getPort().convention(project.getProviders().gradleProperty("awsEmulator.port").map(Integer::parseInt).orElse(9297));
        extension.getRegion().convention("us-east-1");
        extension.getTelemetryEnabled().convention(false);
        extension.getDockerExecutable().convention("docker");
        extension.getMemoryLimit().convention("2g");
        extension.getStartupTimeoutSeconds().convention(60);
        extension.getCommandTimeoutSeconds().convention(60);
        extension.getBuildTimeoutSeconds().convention(600);
        extension.getWorkingDirectory().convention(project.getLayout().getProjectDirectory().dir(".aws-emulator"));
        extension.getDefaultScenario().convention("default");
        return extension;
    }

    private static Provider<AwsEmulatorService> registerService(Project project, AwsEmulatorExtension extension) {
        String owner = EmulatorSettings.hash(project.getProjectDir().getAbsolutePath());
        return project.getGradle().getSharedServices()
            .registerIfAbsent("awsEmulator-" + owner, AwsEmulatorService.class, spec -> {
                AwsEmulatorService.Parameters parameters = spec.getParameters();
                parameters.getOwner().set(owner);
                parameters.getEmulatorVersion().set(extension.getEmulatorVersion());
                parameters.getImage().set(extension.getImage().orElse(""));
                parameters.getPlatform().set(extension.getPlatform());
                parameters.getPort().set(extension.getPort());
                parameters.getRegion().set(extension.getRegion());
                parameters.getTelemetryEnabled().set(extension.getTelemetryEnabled());
                parameters.getDockerExecutable().set(extension.getDockerExecutable());
                parameters.getMemoryLimit().set(extension.getMemoryLimit());
                parameters.getStartupTimeoutSeconds().set(extension.getStartupTimeoutSeconds());
                parameters.getCommandTimeoutSeconds().set(extension.getCommandTimeoutSeconds());
                parameters.getBuildTimeoutSeconds().set(extension.getBuildTimeoutSeconds());
                parameters.getWorkingDirectory().set(extension.getWorkingDirectory());
            });
    }

    private static <T extends AbstractAwsEmulatorTask> void register(Project project, String name,
            Class<T> type, String description) {
        project.getTasks().register(name, type, task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
        });
    }
}
