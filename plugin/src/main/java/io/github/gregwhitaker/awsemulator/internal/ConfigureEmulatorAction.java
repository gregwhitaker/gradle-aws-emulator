package io.github.gregwhitaker.awsemulator.internal;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.ConsumerJvmTrust;

import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorService;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.ProcessForkOptions;
import java.io.Serializable;

/** Holds service and mode providers so connection settings are resolved again on configuration-cache reuse. */
public final class ConfigureEmulatorAction implements Action<Task>, Serializable {
    private final Provider<AwsEmulatorService> runtime;
    private final Provider<EmulatorMode> mode;

    public ConfigureEmulatorAction(Provider<AwsEmulatorService> runtime, Provider<EmulatorMode> mode) {
        this.runtime = runtime;
        this.mode = mode;
    }

    @Override
    public void execute(Task task) {
        EmulatorConnection connection = mode.get() == EmulatorMode.MANAGED
            ? runtime.get().managedConnection() : runtime.get().developmentConnection();
        ProcessForkOptions fork = (ProcessForkOptions) task;
        if (task instanceof JavaForkOptions jvm) {
            var launcher = task instanceof Test test ? test.getJavaLauncher().get() : ((JavaExec) task).getJavaLauncher().get();
            var trustStore = ConsumerJvmTrust.prepare(jvm, launcher.getMetadata().getInstallationPath().getAsFile().toPath(), connection);
            jvm.setSystemProperties(connection.systemProperties(jvm.getSystemProperties(), trustStore));
        }
        fork.setEnvironment(connection.environment(fork.getEnvironment()));
        task.getLogger().lifecycle("AWS Emulator for {}: {}", task.getPath(), connection.endpoint());
    }
}
