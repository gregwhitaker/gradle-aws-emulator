package io.github.gregwhitaker.awsemulator.internal;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;

import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorService;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.process.JavaForkOptions;
import java.io.Serializable;

/** Holds only a build-service provider, so configuration-cache reuse starts a fresh runtime. */
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
        JavaForkOptions fork = (JavaForkOptions) task;
        fork.setEnvironment(connection.environment(fork.getEnvironment()));
        fork.setSystemProperties(connection.systemProperties(fork.getSystemProperties()));
        task.getLogger().lifecycle("AWS Emulator for {}: {}", task.getPath(), connection.endpoint());
    }
}
