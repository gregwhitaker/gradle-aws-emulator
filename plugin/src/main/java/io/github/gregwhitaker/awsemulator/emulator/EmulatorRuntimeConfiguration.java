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

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import javax.inject.Inject;

/** Connection and lifecycle settings shared by the default runtime and named runtimes. */
public abstract class EmulatorRuntimeConfiguration {
    private final ExternalEmulatorConnection external = getObjects().newInstance(ExternalEmulatorConnection.class);

    @Inject protected abstract ObjectFactory getObjects();

    /** AWS Emulator package version to install when building an image. */
    public abstract Property<String> getEmulatorVersion();
    /** Optional existing Docker image; bypasses the generated Dockerfile. */
    public abstract Property<String> getImage();
    /** Docker platform: auto, linux/amd64, or linux/arm64. */
    public abstract Property<String> getPlatform();
    /** Port shared by the host and container. Zero asks the plugin to select a port. */
    public abstract Property<Integer> getPort();
    /** Region supplied to resource clients and opted-in consumers. */
    public abstract Property<String> getRegion();
    /** Whether to enable the emulator's AWS telemetry. */
    public abstract Property<Boolean> getTelemetryEnabled();
    /** Docker command name or absolute executable path. */
    public abstract Property<String> getDockerExecutable();
    /** Container memory limit in Docker's size notation, for example 2g. */
    public abstract Property<String> getMemoryLimit();
    /** HTTPS readiness timeout, including for external connections. */
    public abstract Property<Integer> getStartupTimeoutSeconds();
    /** Timeout for ordinary Docker commands. */
    public abstract Property<Integer> getCommandTimeoutSeconds();
    /** Timeout for Docker image builds and pulls. */
    public abstract Property<Integer> getBuildTimeoutSeconds();
    /** Runtime files, locks, and logs; use a distinct directory for each named runtime. */
    public abstract DirectoryProperty getWorkingDirectory();
    /** Connection to an existing emulator. Disabled unless enabled explicitly or configured with {@link #external(Action)}. */
    public ExternalEmulatorConnection getExternal() { return external; }
    /**
     * Enables an externally owned emulator connection. Both consumer modes use this same endpoint;
     * the plugin never starts, stops, restarts, or cleans up the external emulator.
     * Connection files are read only when an opted-in task executes.
     * @param action configures the HTTPS endpoint, credentials file, and CA certificate file
     */
    public void external(Action<? super ExternalEmulatorConnection> action) {
        external.getEnabled().convention(true);
        action.execute(external);
    }
}
