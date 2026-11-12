package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorRuntime;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** One runtime manager per owner project and Gradle invocation, shared by its explicit consumers. */
public abstract class AwsEmulatorService implements BuildService<AwsEmulatorService.Parameters>, AutoCloseable {
    public interface Parameters extends BuildServiceParameters {
        Property<String> getOwner();
        Property<String> getEmulatorVersion();
        Property<String> getImage();
        Property<String> getPlatform();
        Property<Integer> getPort();
        Property<String> getRegion();
        Property<Boolean> getTelemetryEnabled();
        Property<String> getDockerExecutable();
        Property<String> getMemoryLimit();
        Property<Integer> getStartupTimeoutSeconds();
        Property<Integer> getCommandTimeoutSeconds();
        Property<Integer> getBuildTimeoutSeconds();
        DirectoryProperty getWorkingDirectory();
    }

    private EmulatorRuntime runtime;
    private RuntimeException restartFailure;

    private synchronized EmulatorRuntime runtime() {
        if (runtime == null) {
            Parameters p = getParameters();
            runtime = new EmulatorRuntime(new EmulatorSettings(p.getOwner().get(), p.getEmulatorVersion().get(),
                p.getImage().get(), p.getPlatform().get(), p.getPort().get(), p.getRegion().get(),
                p.getTelemetryEnabled().get(), p.getDockerExecutable().get(), p.getMemoryLimit().get(),
                p.getStartupTimeoutSeconds().get(), p.getCommandTimeoutSeconds().get(), p.getBuildTimeoutSeconds().get(),
                p.getWorkingDirectory().get().getAsFile().toPath()));
        }
        return runtime;
    }

    public EmulatorConnection managedConnection() { return runtime().managedConnection(); }
    public synchronized EmulatorConnection developmentConnection() {
        if (restartFailure != null) throw new GradleException("Cannot seed the development emulator after its restart failed in this build.", restartFailure);
        return runtime().developmentConnection();
    }
    public String prepare() { return "AWS Emulator image ready: " + runtime().prepare(); }
    public String start() { return describe(developmentConnection()); }
    public String stop() {
        runtime().stopDevelopment();
        return "AWS Emulator development instance stopped.";
    }
    public synchronized String restart() {
        try { return describe(runtime().restartDevelopment()); }
        catch (RuntimeException e) { restartFailure = e; throw e; }
    }
    public String status() { return runtime().developmentStatus(); }
    public String logs() { return runtime().developmentLogs(); }
    public String doctor() { return runtime().doctor(); }
    public String cleanup(boolean reportOnly) { return runtime().cleanupStale(reportOnly); }
    public String exportConnection() { return runtime().exportDevelopmentConnection(); }

    private String describe(EmulatorConnection connection) {
        return "AWS Emulator development instance ready at " + connection.endpoint()
            + "\nConnection files: " + connection.directory();
    }

    @Override
    public synchronized void close() {
        if (runtime != null) {
            runtime.close();
        }
    }
}
