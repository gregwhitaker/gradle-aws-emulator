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

package io.github.gregwhitaker.awsemulator.internal.runtime;

import io.github.gregwhitaker.awsemulator.internal.docker.CommandRunner;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerClient;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerClient.Container;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerImageBuilder;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerTunnel;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;

/** Coordinates persistent and build-owned instances, readiness, and cleanup. */
public final class EmulatorRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(EmulatorRuntime.class);
    private final EmulatorSettings settings;
    private final DockerClient docker;
    private final DockerImageBuilder images;
    private final EmulatorWorkspace workspace;
    private final HealthProbe health;
    private Instance managed;
    private EmulatorWorkspace.Lease managedLease;
    private DockerTunnel managedTunnel;

    public EmulatorRuntime(EmulatorSettings settings) {
        this(settings, new ProcessCommandRunner(), new HttpsHealthProbe());
    }

    EmulatorRuntime(EmulatorSettings settings, CommandRunner commands, HealthProbe health) {
        this.settings = settings;
        this.workspace = new EmulatorWorkspace(settings);
        this.docker = new DockerClient(settings, commands);
        this.images = new DockerImageBuilder(settings, workspace, docker);
        this.health = health;
    }

    public synchronized String prepare() { return images.prepare().reference(); }

    public synchronized EmulatorConnection developmentConnection() {
        requireDirectDevelopment();
        return workspace.withLock("development.lock", () -> startDevelopment().connection());
    }

    public synchronized void stopDevelopment() {
        workspace.withLock("development.lock", () -> {
            stopDevelopmentInstance();
            return null;
        });
    }

    public synchronized EmulatorConnection restartDevelopment() {
        requireDirectDevelopment();
        return workspace.withLock("development.lock", () -> {
            stopDevelopmentInstance();
            return startDevelopment().connection();
        });
    }

    public synchronized String developmentStatus() {
        return workspace.withLock("development.lock", () -> {
            Container container = docker.find(settings.developmentName());
            if (container == null) return "AWS Emulator development instance is not running.";
            docker.requireOwner(container);
            Instance instance = instance(container);
            boolean ready = false;
            if (container.running() && instance.connection().filesExist()) {
                try { ready = health.ready(container.port(), instance.connection().trustStore()); }
                catch (Exception ignored) { /* Status does not change the runtime. */ }
            }
            return "AWS Emulator development instance: " + container.state() + ", "
                + (ready ? "healthy" : "not ready") + ", " + instance.connection().endpoint();
        });
    }

    public synchronized String developmentLogs() {
        return workspace.withLock("development.lock", () -> {
            Container container = docker.find(settings.developmentName());
            if (container == null) return "AWS Emulator development instance is not running.";
            docker.requireOwner(container);
            return docker.logs(container.id());
        });
    }

    public synchronized String exportDevelopmentConnection() {
        requireDirectDevelopment();
        return workspace.withLock("development.lock", () -> {
            Instance instance = startDevelopment();
            Path home = instance.container().home();
            try { ConnectionExports.write(instance.connection(), home); }
            catch (IOException e) { throw new GradleException("Cannot export AWS Emulator connection at " + home, e); }
            return "AWS Emulator development connection: " + instance.connection().endpoint()
                + "\nPOSIX shell: " + home.resolve("connection.sh")
                + "\nPowerShell: " + home.resolve("connection.ps1");
        });
    }

    /** Read-only diagnostics: never prepare an image or start a container. */
    public synchronized String doctor() {
        StringBuilder report = new StringBuilder("AWS Emulator doctor\nProject owner: ").append(settings.owner())
            .append("\nWorking directory: ").append(settings.workingDirectory())
            .append("\nArchived logs: ").append(workspace.logsDirectory())
            .append("\nDocker executable: ").append(settings.dockerExecutable())
            .append("\nDocker transport: ").append(settings.docker().transport())
            .append("\nDocker selection: ").append(!settings.docker().context().isEmpty() ? "context " + settings.docker().context()
                : !settings.docker().host().isEmpty() ? settings.docker().host() : "Docker CLI default/environment");
        try {
            String info = docker.daemonInfo();
            report.append("\nDocker daemon: ").append(info);
            if (!info.contains("linux")) report.append("\nACTION: Select a Docker engine running Linux containers.");
            String platform = docker.platform();
            String image = images.reference(platform);
            report.append("\nPlatform: ").append(platform).append("\nImage: ").append(image)
                .append(docker.imageExists(image) ? " (available)" : " (missing; run prepareAwsEmulator)");
            var containers = docker.ownedContainers();
            report.append("\nProject-owned containers: ").append(containers.size());
            for (Container container : containers) {
                report.append("\n  ").append(container.id()).append(container.managed() ? " managed" : " development")
                    .append(" ").append(container.state()).append(" ").append(instance(container).connection().endpoint());
                if (!settings.fingerprint().equals(container.config())) {
                    report.append(" (settings differ; development instances require restartAwsEmulator)");
                }
                if (container.tunneled()) {
                    report.append("\n    Tunnel endpoint and connection files belong to the owning Gradle process.");
                    continue;
                }
                EmulatorConnection connection = instance(container).connection();
                report.append("\n    Connection files: ").append(container.home())
                    .append(connection.filesExist() ? " (present)" : " (missing credentials or TLS files)");
                if (container.running() && connection.filesExist()) {
                    try { report.append("\n    HTTPS health: ").append(health.ready(container.port(), connection.trustStore()) ? "healthy" : "not ready"); }
                    catch (Exception e) { report.append("\n    HTTPS health: ").append(e.getMessage()); }
                }
            }
            if (settings.port() == 0) report.append("\nPort: automatic selection at startup");
            else {
                try (ServerSocket socket = new ServerSocket(settings.port(), 0, InetAddress.getByName("127.0.0.1"))) {
                    report.append("\nConfigured port: ").append(settings.port()).append(" (available)");
                } catch (IOException e) {
                    report.append("\nConfigured port: ").append(settings.port()).append(" (in use; stop the development instance or select port 0)");
                }
            }
        } catch (Exception e) {
            report.append("\nERROR: ").append(e.getMessage());
        }
        return report.append("\nUse cleanupAwsEmulator --report-only to inspect stale managed containers; stopAwsEmulator ends development sessions.").toString();
    }

    /** Removes only stale, project-owned managed instances, protecting active builds with their leases. */
    public synchronized String cleanupStale(boolean reportOnly) {
        StringBuilder report = new StringBuilder(reportOnly ? "Stale AWS Emulator containers (report only):" : "AWS Emulator cleanup:");
        for (Container container : docker.ownedContainers()) {
            if (!container.managed()) {
                report.append("\nSkipped development instance ").append(container.id()).append("; use stopAwsEmulator.");
                continue;
            }
            // A remote runner's filesystem cannot establish whether another client's build is alive.
            if (container.tunneled() && !container.state().equals("exited") && !container.state().equals("dead")) {
                report.append("\nSkipped running tunneled managed instance ").append(container.id())
                    .append("; activity on another Docker client cannot be established.");
                continue;
            }
            try {
                if (container.tunneled() && !Files.exists(container.home())) {
                    // Labels can refer to another runner's filesystem. Never create a lease there.
                    Container current = docker.find(container.id());
                    if (current == null) continue;
                    docker.requireOwner(current);
                    if (!current.managed() || !current.tunneled()
                            || (!current.state().equals("exited") && !current.state().equals("dead"))) continue;
                    if (reportOnly) report.append("\nWould remove stopped tunneled managed instance ").append(current.id());
                    else {
                        archiveLogs(current);
                        docker.remove(current);
                        report.append("\nRemoved stopped tunneled managed instance ").append(current.id());
                    }
                    continue;
                }
                // A legacy running container has no lifetime lease: its active/stale status is unknown.
                boolean hasLease = Files.exists(workspace.managedLock(container.home()));
                if (!hasLease && !container.state().equals("exited") && !container.state().equals("dead")) {
                    report.append("\nSkipped ").append(container.id()).append("; no managed lease, activity cannot be established.");
                    continue;
                }
                try (var lease = workspace.tryManagedLease(container.home(), !hasLease)) {
                    if (lease == null) {
                        report.append("\nSkipped active managed instance ").append(container.id());
                        continue;
                    }
                    Container current = docker.find(container.id());
                    if (current == null) continue;
                    docker.requireOwner(current);
                    if (!current.home().equals(container.home()) || !current.managed()) {
                        throw new GradleException("Container ownership changed during cleanup: " + container.id());
                    }
                    if (current.tunneled() && !current.state().equals("exited") && !current.state().equals("dead")) continue;
                    workspace.requireOwner(current.home());
                    if (reportOnly) report.append("\nWould remove stale managed instance ").append(current.id());
                    else {
                        archiveLogs(current);
                        docker.remove(current);
                        workspace.deleteHome(current.home());
                        report.append("\nRemoved stale managed instance ").append(current.id());
                    }
                }
            } catch (Exception e) {
                throw new GradleException(report + "\nCould not clean up " + container.id() + ": " + e.getMessage(), e);
            }
        }
        return report.append("\nCleanup scan complete.").toString();
    }

    public synchronized EmulatorConnection managedConnection() {
        if (managed == null) {
            String suffix = UUID.randomUUID().toString();
            Path home = workspace.managedHome(suffix);
            managedLease = workspace.tryManagedLease(home, true);
            if (managedLease == null) throw new GradleException("Cannot acquire a new AWS Emulator managed lease.");
            try { managed = start(settings.developmentName() + "-" + suffix, home); }
            catch (RuntimeException e) {
                managedLease.close();
                managedLease = null;
                throw e;
            }
        } else {
            Container current = docker.find(managed.container().id());
            if (current == null || !current.running()) {
                throw new GradleException("The managed AWS Emulator stopped before all consuming tasks finished.");
            }
        }
        return managed.connection();
    }

    private Instance startDevelopment() {
        Container existing = docker.find(settings.developmentName());
        if (existing != null) {
            docker.requireOwner(existing);
            if (!settings.fingerprint().equals(existing.config())) {
                throw new GradleException("The development AWS Emulator uses different settings. Run restartAwsEmulator to apply them.");
            }
            if (existing.running()) {
                Instance instance = instance(existing);
                waitUntilReady(instance);
                return instance;
            }
            docker.remove(existing);
            workspace.deleteHome(existing.home());
        }
        return start(settings.developmentName(), workspace.developmentHome());
    }

    private void stopDevelopmentInstance() {
        Container existing = docker.find(settings.developmentName());
        if (existing != null) {
            docker.remove(existing);
            workspace.deleteHome(existing.home());
        }
    }

    private Instance start(String name, Path home) {
        DockerImageBuilder.Image image = images.prepare();
        DockerTunnel tunnel = settings.docker().tunneled() ? new DockerTunnel(settings.port()) : null;
        int port = tunnel == null ? (settings.port() == 0 ? availablePort() : settings.port()) : tunnel.port();
        try {
            workspace.prepareHome(home);
            Container container = docker.create(name, port, home, image);
            docker.start(container);
            if (tunnel != null) {
                docker.verifyTunnelTools(container);
                tunnel.start(docker, container);
            }
            Instance instance = instance(container);
            waitUntilReady(instance);
            managedTunnel = tunnel;
            LOGGER.lifecycle("AWS Emulator ready at {}", instance.connection().endpoint());
            return instance;
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            String diagnostics = "";
            if (tunnel != null) {
                tunnel.close();
                interrupted |= Thread.interrupted();
                if (!tunnel.diagnostic().isBlank()) failure.addSuppressed(new IOException(tunnel.diagnostic()));
            }
            try {
                Container created = docker.find(name);
                if (created != null && settings.owner().equals(created.owner())
                        && settings.fingerprint().equals(created.config())) {
                    try { diagnostics = docker.logs(created.id()); }
                    catch (Exception logFailure) { failure.addSuppressed(logFailure); }
                    interrupted |= Thread.interrupted();
                    archiveLogs(created);
                    interrupted |= Thread.interrupted();
                    docker.remove(created);
                }
                workspace.deleteHome(home);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw new GradleException("Could not start AWS Emulator on port " + port
                + ". If the port is busy, stop the development instance or set awsEmulator.port to another port (0 selects one).\n"
                + failure.getMessage() + (diagnostics.isBlank() ? "" : "\nContainer logs:\n" + diagnostics), failure);
        }
    }

    private Instance instance(Container container) {
        return new Instance(container, new EmulatorConnection(container.port(), settings.region(),
            container.home().resolve(".aws/emulator/" + container.port())));
    }

    private void waitUntilReady(Instance instance) {
        long deadline = System.nanoTime() + Duration.ofSeconds(settings.startupTimeout()).toNanos();
        String lastError = "Waiting for generated credentials and certificate";
        while (System.nanoTime() < deadline) {
            Container current = docker.find(instance.container().id());
            if (current == null || !current.running()) throw new GradleException("AWS Emulator exited before becoming ready.");
            if (current.tunneled()) {
                // Retry snapshots until HTTPS is ready: a remote file may still have been being written.
                try { docker.copyConnectionFiles(current, instance.connection().directory()); }
                catch (IOException e) { throw new GradleException("Cannot copy AWS Emulator connection files.", e); }
            }
            if (instance.connection().filesExist()) {
                try {
                    if (health.ready(current.port(), instance.connection().trustStore())) {
                        instance.connection().writeHostConfig();
                        return;
                    }
                    lastError = "Health endpoint did not return HTTP 200 with body 'ok'";
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            try { Thread.sleep(200); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted waiting for AWS Emulator readiness.", e);
            }
        }
        throw new GradleException("AWS Emulator did not become ready within " + settings.startupTimeout() + " seconds. " + lastError);
    }

    private int availablePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new GradleException("Cannot select a free AWS Emulator port.", e);
        }
    }

    private void requireDirectDevelopment() {
        if (settings.docker().tunneled()) throw new GradleException("Docker TUNNEL transport supports MANAGED runtimes only. "
            + "Use MANAGED consumers/resource tasks, DIRECT for local persistent development, or an external connection for a persistent remote emulator.");
    }

    private void archiveLogs(Container container) {
        try {
            Files.createDirectories(workspace.logsDirectory());
            Path file = Files.createTempFile(workspace.logsDirectory(),
                (container.managed() ? "managed-" : "development-") + container.id().replaceAll("[^a-zA-Z0-9-]", "_") + "-", ".log");
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            docker.archiveLogs(container, file);
            LOGGER.lifecycle("AWS Emulator container logs saved to {}", file);
        } catch (Exception e) {
            LOGGER.warn("Could not archive AWS Emulator logs for {}: {}. Continuing cleanup.", container.id(), e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (managed == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            if (managedTunnel != null) {
                managedTunnel.close();
                managedTunnel = null;
                interrupted |= Thread.interrupted();
            }
            Container current = docker.find(managed.container().id());
            if (current != null) {
                archiveLogs(current);
                interrupted |= Thread.interrupted();
                docker.remove(current);
            }
            workspace.deleteHome(managed.container().home());
            managed = null;
        } finally {
            if (managedLease != null) {
                managedLease.close();
                managedLease = null;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Instance(Container container, EmulatorConnection connection) {}
}
