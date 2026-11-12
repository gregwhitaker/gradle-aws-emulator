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
    void developmentInstanceSurvivesBuildAndManagedInstanceIsRemoved() {
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
        assertTrue(docker.containers.containsKey(settings().developmentName()));
        runtime.stopDevelopment();
        runtime.stopDevelopment();
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    void startupTimeoutRemovesContainerAndGeneratedFiles() throws Exception {
        FakeDocker docker = new FakeDocker();
        EmulatorRuntime runtime = new EmulatorRuntime(settings(), docker, (port, store) -> false);
        GradleException failure = assertThrows(GradleException.class, runtime::managedConnection);
        assertTrue(failure.getMessage().contains("did not become ready"));
        assertTrue(failure.getMessage().contains("fake emulator log"));
        assertTrue(docker.containers.isEmpty());
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

    private static String after(List<String> list, String option) { return list.get(list.indexOf(option) + 1); }

    private static class FakeContainer {
        final String id, owner, config, port;
        final Path home;
        String state = "running";
        FakeContainer(String id, String owner, String config, String port, Path home) {
            this.id = id; this.owner = owner; this.config = config; this.port = port; this.home = home;
        }
    }

    private static class FakeDocker implements CommandRunner {
        final Map<String, FakeContainer> containers = new LinkedHashMap<>();
        final List<List<String>> calls = new ArrayList<>();
        int sequence;
        boolean failStart;

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
                    labels.get(DockerClient.CONFIG_LABEL), labels.get(DockerClient.PORT_LABEL), home);
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
                FakeContainer found = filter.startsWith("name=")
                    ? containers.get(filter.substring("name=^/".length(), filter.length() - 1))
                    : containers.values().stream().filter(c -> c.id.equals(filter.substring(3))).findFirst().orElse(null);
                return new Result(0, found == null ? "" : found.id);
            }
            String id = args.get(args.size() - 1);
            FakeContainer container = containers.values().stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow();
            return switch (operation) {
                case "inspect" -> new Result(0, String.join("\n", container.id, container.owner, container.config, container.port, container.home.toString(), container.state));
                case "start" -> new Result(failStart ? 1 : 0, failStart ? "port is already allocated" : id);
                case "logs" -> new Result(0, "fake emulator log");
                case "stop" -> { container.state = "exited"; yield new Result(0, id); }
                case "rm" -> { containers.values().remove(container); yield new Result(0, id); }
                default -> throw new AssertionError("Unexpected command " + command);
            };
        }
    }
}
