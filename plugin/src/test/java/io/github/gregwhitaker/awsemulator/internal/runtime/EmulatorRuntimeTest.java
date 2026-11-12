package io.github.gregwhitaker.awsemulator.internal.runtime;

import io.github.gregwhitaker.awsemulator.internal.docker.CommandRunner;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerClient;
import io.github.gregwhitaker.awsemulator.internal.docker.DockerImageBuilder;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorRuntimeTest {
    @TempDir Path directory;

    private EmulatorSettings settings() {
        return new EmulatorSettings("test-owner", "1.0.322", "", "auto", 0, "us-east-1", false,
            "docker", "2g", 1, 5, 10, directory);
    }

    @Test
    void developmentInstanceSurvivesBuildAndManagedInstanceIsRemoved() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        assertTrue(runtime.developmentConnection().endpoint().startsWith("https://127.0.0.1:"));
        assertTrue(runtime.developmentConnection().endpoint().startsWith("https://127.0.0.1:"));
        assertEquals(1, docker.containers.size());
        EmulatorConnection first = runtime.managedConnection();
        assertSame(first, runtime.managedConnection());
        assertEquals(2, docker.containers.size());
        assertTrue(runtime.developmentStatus().contains("healthy"));
        assertTrue(runtime.developmentLogs().contains("fake emulator log"));
        runtime.close();
        assertEquals(1, docker.containers.size());
        assertFalse(Files.exists(first.directory()));
        assertEquals("fake emulator log", archivedLogs());
        int logs = docker.calls.indexOf(docker.calls.stream().filter(c -> c.contains("--timestamps")).findFirst().orElseThrow());
        int remove = docker.calls.indexOf(docker.calls.stream().filter(c -> c.contains("rm")).findFirst().orElseThrow());
        assertTrue(logs < remove, "Logs must be archived before removing the container");
        assertTrue(docker.containers.containsKey(settings().developmentName()));
        runtime.stopDevelopment();
        runtime.stopDevelopment();
        assertTrue(docker.containers.isEmpty());
        assertEquals("fake emulator log", archivedLogs());
    }

    @Test
    void startupTimeoutRemovesContainerAndGeneratedFiles() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> false);
        GradleException failure = assertThrows(GradleException.class, runtime::managedConnection);
        assertTrue(failure.getMessage().contains("did not become ready"));
        assertTrue(failure.getMessage().contains("fake emulator log"));
        assertTrue(docker.containers.isEmpty());
        assertEquals("fake emulator log", archivedLogs());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
        }
    }

    @Test
    void failedDockerStartCleansUpTheCreatedContainer() {
        FakeDocker docker = new FakeDocker();
        docker.failStart = true;
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        assertThrows(GradleException.class, runtime::managedConnection);
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    void refusesToStopForeignContainerWithTheSameName() {
        FakeDocker docker = new FakeDocker();
        docker.containers.put(settings().developmentName(), new FakeContainer("other", "foreign", "config", "9297", directory));
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        assertThrows(GradleException.class, () -> runtime.stopDevelopment());
        assertEquals(1, docker.containers.size());
        assertFalse(docker.calls.stream().anyMatch(call -> call.contains("stop") || call.contains("rm")));
    }

    @Test
    void restartReplacesDevelopmentStateAndCredentials() {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        runtime.developmentConnection();
        String firstId = docker.containers.get(settings().developmentName()).id;
        runtime.restartDevelopment();
        assertNotEquals(firstId, docker.containers.get(settings().developmentName()).id);
        runtime.stopDevelopment();
    }

    @Test
    void dockerArgumentsPinThePackageAndKeepPortsAndOwnershipConsistent() {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        var connection = runtime.managedConnection();
        List<String> create = docker.calls.stream().filter(c -> c.contains("create")).findFirst().orElseThrow();
        assertEquals("127.0.0.1:" + connection.port() + ":" + connection.port(), after(create, "--publish"));
        assertEquals(Integer.toString(connection.port()), after(create, "--port"));
        assertEquals("on", after(create, "--auth"));
        assertEquals("off", after(create, "--telemetry"));
        assertTrue(DockerImageBuilder.dockerfile("1.0.322", "linux/arm64").contains("aws-emulator-1.0.322-1-aarch64.rpm"));
        assertTrue(DockerImageBuilder.dockerfile("1.0.322", "linux/amd64").contains("aws-emulator-1.0.322-1-x86_64.rpm"));
        runtime.close();
    }

    @Test
    void rejectsInvalidPortBeforeExecutingDocker() {
        assertThrows(GradleException.class, () -> new EmulatorSettings("owner", "1.0.322", "", "auto", 65536,
            "us-east-1", false, "docker", "2g", 1, 1, 1, directory));
    }

    @Test
    void changingWorkingDirectoryCleansUpTheOriginalHomeAndPreservesUnownedFiles() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime original = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        original.developmentConnection();
        Path replacement = directory.resolve("replacement");
        Path unowned = replacement.resolve("development/keep.txt");
        Files.createDirectories(unowned.getParent());
        Files.writeString(unowned, "user data");
        var changedSettings = new EmulatorSettings("test-owner", "1.0.322", "", "auto", 0, "us-east-1", false,
            "docker", "2g", 1, 5, 10, replacement);
        EmulatorRuntime changed = new EmulatorRuntime(changedSettings, docker, (port, store) -> true);
        changed.stopDevelopment();
        assertFalse(Files.exists(directory.resolve("development")));
        assertEquals("user data", Files.readString(unowned));
        assertThrows(GradleException.class, () -> changed.developmentConnection());
        assertEquals("user data", Files.readString(unowned));
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    void logArchiveFailuresDoNotPreventManagedCleanup() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        var connection = runtime.managedConnection();
        docker.failLogs = true;
        runtime.close();
        assertTrue(docker.containers.isEmpty());
        assertFalse(Files.exists(connection.directory()));

        var second = runtime.managedConnection();
        docker.failLogs = false;
        try (var files = Files.list(directory.resolve("logs"))) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(directory.resolve("logs"));
        Files.writeString(directory.resolve("logs"), "unwritable archive directory");
        runtime.close();
        assertTrue(docker.containers.isEmpty());
        assertFalse(Files.exists(second.directory()));
    }

    @Test
    void cleanupSkipsActiveAndDevelopmentAndRemovesOnlyStaleOwnedManagedContainers() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime active = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        active.developmentConnection();
        active.managedConnection();
        Path staleHome = directory.resolve("managed-stale");
        EmulatorWorkspace workspace = new EmulatorWorkspace(settings());
        workspace.prepareHome(staleHome);
        try (var lease = workspace.tryManagedLease(staleHome, true)) { assertNotNull(lease); }
        docker.containers.put("stale", new FakeContainer("stale", "test-owner", "old-config", "9211", staleHome, "managed"));
        docker.containers.put("foreign", new FakeContainer("foreign", "another-project", "config", "9212", directory.resolve("foreign"), "managed"));
        EmulatorRuntime cleanup = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        assertTrue(cleanup.cleanupStale(true).contains("Would remove stale managed instance stale"));
        assertTrue(Files.exists(staleHome));
        assertEquals(4, docker.containers.size());
        String report = cleanup.cleanupStale(false);
        assertTrue(report.contains("Skipped active managed instance"), report);
        assertTrue(report.contains("Skipped development instance"), report);
        assertTrue(report.contains("Removed stale managed instance stale"), report);
        assertEquals(3, docker.containers.size());
        assertFalse(Files.exists(staleHome));
        assertEquals("fake emulator log", archivedLogs());
        assertFalse(cleanup.cleanupStale(false).contains("Removed"));
        active.close();
        active.stopDevelopment();
        assertTrue(docker.containers.containsKey("foreign"));
    }

    @Test
    void cleanupLeavesRunningLegacyContainersAndUnownedDirectoriesAlone() throws Exception {
        FakeDocker docker = new FakeDocker();
        Path home = directory.resolve("managed-legacy");
        new EmulatorWorkspace(settings()).prepareHome(home);
        FakeContainer legacy = new FakeContainer("legacy", "test-owner", "old", "9297", home, "<no value>");
        docker.containers.put("legacy", legacy);
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        assertTrue(runtime.cleanupStale(false).contains("no managed lease"));
        assertTrue(docker.containers.containsKey("legacy"));
        legacy.state = "exited";
        assertTrue(runtime.cleanupStale(false).contains("Removed stale"));

        Path unowned = directory.resolve("managed-unowned");
        Files.createDirectories(unowned);
        Files.writeString(unowned.resolve("keep.txt"), "user data");
        FakeContainer other = new FakeContainer("unowned", "test-owner", "old", "9298", unowned, "managed");
        other.state = "exited";
        docker.containers.put("unowned", other);
        assertThrows(GradleException.class, () -> runtime.cleanupStale(false));
        assertEquals("user data", Files.readString(unowned.resolve("keep.txt")));
        assertTrue(docker.containers.containsKey("unowned"));
    }

    @Test
    void doctorInspectsWithoutStartingOrWritingAndExplainsDockerFailures() {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> true);
        String report = runtime.doctor();
        assertTrue(report.contains("Docker daemon:"), report);
        assertTrue(report.contains("Image:"), report);
        assertTrue(report.contains("Project-owned containers: 0"), report);
        assertFalse(docker.calls.stream().anyMatch(c -> c.contains("create") || c.contains("build") || c.contains("pull")));
        assertFalse(Files.exists(directory.resolve("images")));
        var unavailable = new EmulatorRuntime(settings(), (command, timeout) -> new CommandRunner.Result(1, "daemon unavailable"), (port, store) -> true);
        assertTrue(unavailable.doctor().contains("daemon unavailable"));
    }

    @Test
    void checkingAnActiveLeaseDoesNotReleaseItsCrossProcessLock() throws Exception {
        EmulatorWorkspace workspace = new EmulatorWorkspace(settings());
        Path home = directory.resolve("managed-active");
        try (var lease = workspace.tryManagedLease(home, true)) {
            assertNotNull(lease);
            assertNull(workspace.tryManagedLease(home, false));
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            String classes = Path.of(LeaseProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            var result = new io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner().run(
                List.of(java, "-cp", classes, LeaseProbe.class.getName(), workspace.managedLock(home).toString()), Duration.ofSeconds(10));
            assertEquals(3, result.exitCode(), "An external process must still see the active lease");
        }
        try (var lease = workspace.tryManagedLease(home, false)) { assertNotNull(lease); }
    }

    public static class LeaseProbe {
        public static void main(String[] args) throws Exception {
            try (var channel = java.nio.channels.FileChannel.open(Path.of(args[0]), java.nio.file.StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                System.exit(lock == null ? 3 : 0);
            }
        }
    }

    private String archivedLogs() throws Exception {
        try (var files = Files.list(directory.resolve("logs"))) {
            return Files.readString(files.findFirst().orElseThrow());
        }
    }

    private static String after(List<String> list, String option) { return list.get(list.indexOf(option) + 1); }

    private static class FakeContainer {
        final String id, owner, config, port, mode;
        final Path home;
        String state = "running";
        FakeContainer(String id, String owner, String config, String port, Path home) {
            this(id, owner, config, port, home, "development");
        }
        FakeContainer(String id, String owner, String config, String port, Path home, String mode) {
            this.id = id; this.owner = owner; this.config = config; this.port = port; this.home = home; this.mode = mode;
        }
    }

    private static class FakeDocker implements CommandRunner {
        final Map<String, FakeContainer> containers = new LinkedHashMap<>();
        final List<List<String>> calls = new ArrayList<>();
        int sequence;
        boolean failStart;
        boolean failLogs;

        @Override
        public Result run(List<String> command, Duration timeout) {
            calls.add(List.copyOf(command));
            if (command.get(0).equals("id")) return new Result(0, "1000");
            List<String> args = command.subList(1, command.size());
            if (args.get(0).equals("info")) return new Result(0, "aarch64");
            if (args.get(0).equals("image")) return new Result(0, "existing-image");
            String operation = args.get(1);
            if (operation.equals("create")) {
                Map<String, String> labels = new LinkedHashMap<>();
                for (int i = 0; i < args.size(); i++) if (args.get(i).equals("--label")) {
                    String[] label = args.get(++i).split("=", 2); labels.put(label[0], label[1]);
                }
                String mount = after(args, "--mount");
                Path home = Path.of(mount.substring("type=bind,source=".length(), mount.indexOf(",target=")));
                FakeContainer container = new FakeContainer("id" + (++sequence), labels.get(DockerClient.OWNER_LABEL),
                    labels.get(DockerClient.CONFIG_LABEL), labels.get(DockerClient.PORT_LABEL), home, labels.get(DockerClient.MODE_LABEL));
                containers.put(after(args, "--name"), container);
                try {
                    Path files = home.resolve(".aws/emulator/" + container.port);
                    Files.createDirectories(files);
                    for (String file : List.of("ca.pem", "truststore.p12", "emulator_credentials")) Files.writeString(files.resolve(file), "fixture");
                } catch (Exception e) { throw new RuntimeException(e); }
                return new Result(0, container.id);
            }
            if (operation.equals("ls")) {
                String filter = after(args, "--filter");
                if (filter.startsWith("label=")) {
                    return new Result(0, containers.values().stream().filter(c -> filter.equals("label=" + DockerClient.OWNER_LABEL + "=" + c.owner))
                        .map(c -> c.id).collect(java.util.stream.Collectors.joining("\n")));
                }
                FakeContainer found = filter.startsWith("name=")
                    ? containers.get(filter.substring("name=^/".length(), filter.length() - 1))
                    : containers.values().stream().filter(c -> c.id.equals(filter.substring(3))).findFirst().orElse(null);
                return new Result(0, found == null ? "" : found.id);
            }
            String id = args.get(args.size() - 1);
            FakeContainer container = containers.values().stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow();
            return switch (operation) {
                case "inspect" -> new Result(0, String.join("\n", container.id, container.owner, container.config, container.port, container.home.toString(), container.state, container.mode));
                case "start" -> new Result(failStart ? 1 : 0, failStart ? "port is already allocated" : id);
                case "logs" -> new Result(failLogs ? 1 : 0, failLogs ? "log driver unavailable" : "fake emulator log");
                case "stop" -> { container.state = "exited"; yield new Result(0, id); }
                case "rm" -> { containers.values().remove(container); yield new Result(0, id); }
                default -> throw new AssertionError("Unexpected command " + command);
            };
        }
    }
}
