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
        Provider<AwsEmulatorService> service = registerService(project, extension, AwsEmulatorExtension.DEFAULT_RUNTIME);
        extension.attach(project, service);
        project.getTasks().withType(AbstractAwsEmulatorTask.class).configureEach(extension::configureRuntime);
        ReportModule.load(project);
        registerLifecycleTasks(project, AwsEmulatorExtension.DEFAULT_RUNTIME);
        extension.getRuntimes().all(runtime -> {
            inheritSettings(extension, runtime);
            extension.registerRuntime(runtime.getName(), registerService(project, runtime, runtime.getName()));
        });
        ScenarioModule.load(project, extension);
    }

    static void registerLifecycleTasks(Project project, String runtimeName) {
        register(project, runtimeName, PREPARE_TASK_NAME, PrepareAwsEmulatorTask.class, "Builds or locates the AWS Emulator Docker image.");
        register(project, runtimeName, START_TASK_NAME, StartAwsEmulatorTask.class, "Starts the project's persistent development emulator.");
        register(project, runtimeName, STOP_TASK_NAME, StopAwsEmulatorTask.class, "Stops the project's persistent development emulator.");
        register(project, runtimeName, RESTART_TASK_NAME, RestartAwsEmulatorTask.class, "Restarts the development emulator with empty state.");
        register(project, runtimeName, STATUS_TASK_NAME, StatusAwsEmulatorTask.class, "Reports the development emulator's status and endpoint.");
        register(project, runtimeName, LOGS_TASK_NAME, LogsAwsEmulatorTask.class, "Displays recent development emulator logs.");
        register(project, runtimeName, "doctorAwsEmulator", DoctorAwsEmulatorTask.class, "Diagnoses Docker, images, ports, and project-owned emulator connections.");
        register(project, runtimeName, "cleanupAwsEmulator", CleanupAwsEmulatorTask.class, "Archives logs and removes stale project-owned managed containers.");
        register(project, runtimeName, "exportAwsEmulatorConnection", ExportAwsEmulatorConnectionTask.class, "Exports development connection settings for POSIX shells and PowerShell.");
        register(project, runtimeName, "reportAwsEmulator", ReportAwsEmulatorTask.class, "Writes HTML and JSON inventories of an existing AWS Emulator.");
        project.getTasks().named("reportAwsEmulator" + NamedEmulatorRuntime.taskSuffix(runtimeName), ReportAwsEmulatorTask.class)
            .configure(task -> task.getMode().convention(io.github.gregwhitaker.awsemulator.model.EmulatorMode.DEVELOPMENT));
    }

    private static AwsEmulatorExtension registerExtension(Project project) {
        AwsEmulatorExtension extension = project.getExtensions().create("awsEmulator", AwsEmulatorExtension.class);
        extension.getEmulatorVersion().convention("1.0.322");
        extension.getPlatform().convention("auto");
        extension.getPort().convention(project.getProviders().gradleProperty("awsEmulator.port").map(Integer::parseInt).orElse(0));
        extension.getRegion().convention("us-east-1");
        extension.getTelemetryEnabled().convention(false);
        extension.getDockerExecutable().convention("docker");
        extension.getDocker().getHost().convention("");
        extension.getDocker().getContext().convention("");
        extension.getDocker().getTransport().convention(io.github.gregwhitaker.awsemulator.model.DockerTransport.DIRECT);
        extension.getMemoryLimit().convention("2g");
        extension.getStartupTimeoutSeconds().convention(60);
        extension.getCommandTimeoutSeconds().convention(60);
        extension.getBuildTimeoutSeconds().convention(600);
        extension.getWorkingDirectory().convention(project.getLayout().getProjectDirectory().dir(".aws-emulator"));
        extension.getDefaultScenario().convention("default");
        return extension;
    }

    private static void inheritSettings(AwsEmulatorExtension defaults, NamedEmulatorRuntime runtime) {
        runtime.getEmulatorVersion().convention(defaults.getEmulatorVersion());
        runtime.getImage().convention(defaults.getImage());
        runtime.getPlatform().convention(defaults.getPlatform());
        runtime.getPort().convention(0);
        runtime.getRegion().convention(defaults.getRegion());
        runtime.getTelemetryEnabled().convention(defaults.getTelemetryEnabled());
        runtime.getDockerExecutable().convention(defaults.getDockerExecutable());
        runtime.getDocker().getHost().convention(defaults.getDocker().getHost());
        runtime.getDocker().getContext().convention(defaults.getDocker().getContext());
        runtime.getDocker().getTransport().convention(defaults.getDocker().getTransport());
        runtime.getMemoryLimit().convention(defaults.getMemoryLimit());
        runtime.getStartupTimeoutSeconds().convention(defaults.getStartupTimeoutSeconds());
        runtime.getCommandTimeoutSeconds().convention(defaults.getCommandTimeoutSeconds());
        runtime.getBuildTimeoutSeconds().convention(defaults.getBuildTimeoutSeconds());
        runtime.getWorkingDirectory().convention(defaults.getWorkingDirectory().dir("runtimes/" + runtime.getName()));
        // External connections are deliberately independent, allowing managed tests alongside an external default.
    }

    private static Provider<AwsEmulatorService> registerService(Project project, EmulatorRuntimeConfiguration extension, String name) {
        String identity = project.getProjectDir().getAbsolutePath();
        if (!AwsEmulatorExtension.DEFAULT_RUNTIME.equals(name)) identity += "\n" + name;
        String owner = EmulatorSettings.hash(identity);
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
                parameters.getDockerHost().set(extension.getDocker().getHost());
                parameters.getDockerContext().set(extension.getDocker().getContext());
                parameters.getDockerTransport().set(extension.getDocker().getTransport());
                parameters.getMemoryLimit().set(extension.getMemoryLimit());
                parameters.getStartupTimeoutSeconds().set(extension.getStartupTimeoutSeconds());
                parameters.getCommandTimeoutSeconds().set(extension.getCommandTimeoutSeconds());
                parameters.getBuildTimeoutSeconds().set(extension.getBuildTimeoutSeconds());
                parameters.getWorkingDirectory().set(extension.getWorkingDirectory());
                parameters.getExternalEnabled().set(extension.getExternal().getEnabled());
                parameters.getExternalEndpoint().set(extension.getExternal().getEndpoint());
                parameters.getExternalCredentialsFile().set(extension.getExternal().getCredentialsFile());
                parameters.getExternalCertificateFile().set(extension.getExternal().getCertificateFile());
            });
    }

    private static <T extends AbstractAwsEmulatorTask> void register(Project project, String runtimeName, String name,
            Class<T> type, String description) {
        project.getTasks().register(name + NamedEmulatorRuntime.taskSuffix(runtimeName), type, task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
            if (!AwsEmulatorExtension.DEFAULT_RUNTIME.equals(runtimeName)) {
                task.getRuntimeName().set(runtimeName);
                task.getRuntimeName().disallowChanges();
            }
        });
    }
}
