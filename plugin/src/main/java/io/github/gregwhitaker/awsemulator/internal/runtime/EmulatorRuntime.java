package io.github.gregwhitaker.awsemulator.internal.runtime;

import io.github.gregwhitaker.awsemulator.internal.docker.CommandRunner;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerClient;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerClient.Container;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerImageBuilder;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
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
        return workspace.withLock("development.lock", () -> startDevelopment().connection());
    }

    public synchronized void stopDevelopment() {
        workspace.withLock("development.lock", () -> {
            stopDevelopmentInstance();
            return null;
        });
    }

    public synchronized EmulatorConnection restartDevelopment() {
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

    public synchronized EmulatorConnection managedConnection() {
        if (managed == null) {
            String suffix = UUID.randomUUID().toString();
            managed = start(settings.developmentName() + "-" + suffix, workspace.managedHome(suffix));
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
        int port = settings.port() == 0 ? availablePort() : settings.port();
        workspace.prepareHome(home);
        try {
            Container container = docker.create(name, port, home, image);
            docker.start(container);
            Instance instance = instance(container);
            waitUntilReady(instance);
            LOGGER.lifecycle("AWS Emulator ready at {}", instance.connection().endpoint());
            return instance;
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            String diagnostics = "";
            try {
                Container created = docker.find(name);
                if (created != null && settings.owner().equals(created.owner())
                        && settings.fingerprint().equals(created.config())) {
                    try { diagnostics = docker.logs(created.id()); }
                    catch (Exception logFailure) { failure.addSuppressed(logFailure); }
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

    @Override
    public synchronized void close() {
        if (managed == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            Container current = docker.find(managed.container().id());
            if (current != null) docker.remove(current);
            workspace.deleteHome(managed.container().home());
            managed = null;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Instance(Container container, EmulatorConnection connection) {}
}
