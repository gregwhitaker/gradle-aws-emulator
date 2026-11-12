package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.internal.ConfigureEmulatorAction;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/** Configuration for the AWS Emulator runtime and its opted-in consumers. */
public abstract class AwsEmulatorExtension {
    private Provider<AwsEmulatorService> service;

    public abstract Property<String> getEmulatorVersion();
    /** Optional existing Docker image; bypasses the generated Dockerfile. */
    public abstract Property<String> getImage();
    /** Docker platform: auto, linux/amd64, or linux/arm64. */
    public abstract Property<String> getPlatform();
    /** Port shared by the host and container. Zero asks the plugin to select a port. */
    public abstract Property<Integer> getPort();
    public abstract Property<String> getRegion();
    public abstract Property<Boolean> getTelemetryEnabled();
    public abstract Property<String> getDockerExecutable();
    public abstract Property<String> getMemoryLimit();
    public abstract Property<Integer> getStartupTimeoutSeconds();
    public abstract Property<Integer> getCommandTimeoutSeconds();
    public abstract Property<Integer> getBuildTimeoutSeconds();
    public abstract DirectoryProperty getWorkingDirectory();

    void attach(Provider<AwsEmulatorService> service) {
        this.service = service;
    }

    /** Starts a build-owned emulator for this Test or JavaExec task, and configures its forked JVM. */
    public void useWith(TaskProvider<? extends Task> taskProvider) {
        Provider<AwsEmulatorService> runtime = service;
        taskProvider.configure(task -> {
            if (!(task instanceof Test) && !(task instanceof JavaExec)) {
                throw new GradleException("awsEmulator.useWith requires a Test or JavaExec task: " + task.getPath());
            }
            if (task.getExtensions().getExtraProperties().has("awsEmulatorConfigured")) {
                return;
            }
            task.getExtensions().getExtraProperties().set("awsEmulatorConfigured", true);
            task.usesService(runtime);
            task.doFirst(new ConfigureEmulatorAction(runtime));
            // Emulator state is external and starts empty on every managed run.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getOutputs().doNotCacheIf("Uses a live AWS Emulator", ignored -> true);
        });
    }
}
