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

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorRuntime;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.internal.runtime.ExternalConnection;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerOptions;
import io.github.gregwhitaker.awsemulator.model.DockerTransport;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
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
        Property<String> getDockerHost();
        Property<String> getDockerContext();
        Property<DockerTransport> getDockerTransport();
        Property<String> getMemoryLimit();
        Property<Integer> getStartupTimeoutSeconds();
        Property<Integer> getCommandTimeoutSeconds();
        Property<Integer> getBuildTimeoutSeconds();
        DirectoryProperty getWorkingDirectory();
        Property<Boolean> getExternalEnabled();
        Property<String> getExternalEndpoint();
        RegularFileProperty getExternalCredentialsFile();
        RegularFileProperty getExternalCertificateFile();
    }

    private EmulatorRuntime runtime;
    private ExternalConnection external;
    private RuntimeException restartFailure;

    private synchronized EmulatorRuntime runtime() {
        requireOwnedRuntime("manage the emulator lifecycle");
        if (runtime == null) {
            Parameters p = getParameters();
            runtime = new EmulatorRuntime(new EmulatorSettings(p.getOwner().get(), p.getEmulatorVersion().get(),
                p.getImage().get(), p.getPlatform().get(), p.getPort().get(), p.getRegion().get(),
                p.getTelemetryEnabled().get(), p.getDockerExecutable().get(), p.getMemoryLimit().get(),
                p.getStartupTimeoutSeconds().get(), p.getCommandTimeoutSeconds().get(), p.getBuildTimeoutSeconds().get(),
                p.getWorkingDirectory().get().getAsFile().toPath(),
                new DockerOptions(p.getDockerHost().getOrElse(""), p.getDockerContext().getOrElse(""),
                    p.getDockerTransport().getOrElse(DockerTransport.DIRECT))));
        }
        return runtime;
    }

    private boolean isExternal() { return getParameters().getExternalEnabled().getOrElse(false); }

    private synchronized ExternalConnection external() {
        if (external == null) {
            Parameters p = getParameters();
            if (!p.getExternalEndpoint().isPresent() || !p.getExternalCredentialsFile().isPresent()
                    || !p.getExternalCertificateFile().isPresent()) {
                throw new GradleException("awsEmulator.external requires endpoint, credentialsFile, and certificateFile. "
                    + "The external emulator must already be running; Docker will not be invoked.");
            }
            external = new ExternalConnection(p.getExternalEndpoint().get(), p.getRegion().get(),
                p.getWorkingDirectory().get().getAsFile().toPath(), p.getExternalCredentialsFile().get().getAsFile().toPath(),
                p.getExternalCertificateFile().get().getAsFile().toPath(), p.getStartupTimeoutSeconds().get());
        }
        return external;
    }

    private void requireOwnedRuntime(String operation) {
        if (isExternal()) throw new GradleException("Cannot " + operation + " when awsEmulator.external is enabled. "
            + "The emulator lifecycle and logs are managed externally.");
    }

    public EmulatorConnection managedConnection() { return isExternal() ? external().connect() : runtime().managedConnection(); }
    public synchronized EmulatorConnection developmentConnection() {
        if (restartFailure != null) throw new GradleException("Cannot seed the development emulator after its restart failed in this build.", restartFailure);
        return isExternal() ? external().connect() : runtime().developmentConnection();
    }
    public String prepare() {
        requireOwnedRuntime("prepareAwsEmulator");
        return "AWS Emulator image ready: " + runtime().prepare();
    }
    public String start() {
        requireOwnedRuntime("startAwsEmulator");
        return describe(developmentConnection());
    }
    public String stop() {
        requireOwnedRuntime("stopAwsEmulator");
        runtime().stopDevelopment();
        return "AWS Emulator development instance stopped.";
    }
    public synchronized String restart() {
        try {
            requireOwnedRuntime("restartAwsEmulator");
            return describe(runtime().restartDevelopment());
        }
        catch (RuntimeException e) { restartFailure = e; throw e; }
    }
    public String status() { return isExternal() ? external().status() : runtime().developmentStatus(); }
    public String logs() {
        requireOwnedRuntime("logsAwsEmulator");
        return runtime().developmentLogs();
    }
    public String doctor() {
        if (!isExternal()) return runtime().doctor();
        try { return external().status(); }
        catch (RuntimeException e) { return "AWS Emulator external connection: ERROR: " + e.getMessage(); }
    }
    public String cleanup(boolean reportOnly) {
        requireOwnedRuntime("cleanupAwsEmulator");
        return runtime().cleanupStale(reportOnly);
    }
    public String exportConnection() { return isExternal() ? external().exportConnection() : runtime().exportDevelopmentConnection(); }

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
