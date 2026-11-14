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

package io.github.gregwhitaker.awsemulator.internal.docker;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.api.GradleException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Docker CLI adapter. All commands use argument lists and bounded execution. */
public final class DockerClient {
    public static final String OWNER_LABEL = "io.github.gregwhitaker.aws-emulator.owner";
    public static final String CONFIG_LABEL = "io.github.gregwhitaker.aws-emulator.config";
    public static final String PORT_LABEL = "io.github.gregwhitaker.aws-emulator.port";
    public static final String HOME_LABEL = "io.github.gregwhitaker.aws-emulator.home";
    public static final String MODE_LABEL = "io.github.gregwhitaker.aws-emulator.mode";
    public static final String TRANSPORT_LABEL = "io.github.gregwhitaker.aws-emulator.transport";
    public static final int TUNNEL_PORT = 9297;
    private final EmulatorSettings settings;
    private final CommandRunner commands;

    public DockerClient(EmulatorSettings settings, CommandRunner commands) {
        this.settings = settings;
        this.commands = commands;
    }

    public String platform() {
        if (!settings.platform().equals("auto")) return settings.platform();
        String architecture = run("info", "--format", "{{.Architecture}}").requireSuccess("Reading Docker architecture");
        return switch (architecture) {
            case "aarch64", "arm64" -> "linux/arm64";
            case "x86_64", "amd64" -> "linux/amd64";
            default -> throw new GradleException("Unsupported Docker architecture: " + architecture);
        };
    }

    public String daemonInfo() {
        return run("info", "--format", "{{.ServerVersion}} {{.OSType}} {{.Architecture}}")
            .requireSuccess("Checking Docker daemon (start Docker and use Linux containers)");
    }

    public boolean imageExists(String image) {
        CommandRunner.Result result = run("image", "inspect", "--format", "{{.Id}}", image);
        if (result.exitCode() == 0) return true;
        if (result.output().contains("No such image") || result.output().contains("No such object")) return false;
        result.requireSuccess("Inspecting Docker image");
        return false;
    }

    public void pullImage(String image, String platform) {
        run(List.of("pull", "--platform", platform, image), settings.buildTimeout()).requireSuccess("Pulling configured AWS Emulator image");
    }

    public void buildImage(String image, String platform, Path context) {
        run(List.of("build", "--platform", platform, "--tag", image, context.toString()), settings.buildTimeout())
            .requireSuccess("Building AWS Emulator image");
    }

    public Container create(String name, int port, Path home, DockerImageBuilder.Image image) {
        String mode = name.equals(settings.developmentName()) ? "development" : "managed";
        boolean tunneled = settings.docker().tunneled();
        List<String> args = new ArrayList<>(List.of("container", "create", "--name", name,
            "--platform", image.platform(), "--label", OWNER_LABEL + "=" + settings.owner(),
            "--label", CONFIG_LABEL + "=" + settings.fingerprint(), "--label", PORT_LABEL + "=" + port,
            "--label", HOME_LABEL + "=" + home, "--label", MODE_LABEL + "=" + mode,
            "--label", TRANSPORT_LABEL + "=" + (tunneled ? "tunnel" : "direct"),
            "--memory", settings.memoryLimit(), "--memory-swap", settings.memoryLimit(),
            "--env", "HOME=/home/emulator"));
        if (!tunneled) args.addAll(List.of("--publish", "127.0.0.1:" + port + ":" + port,
            "--mount", "type=bind,source=" + home + ",target=/home/emulator"));
        if (!tunneled && System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            String uid = commands.run(List.of("id", "-u"), Duration.ofSeconds(5)).requireSuccess("Resolving uid");
            String gid = commands.run(List.of("id", "-g"), Duration.ofSeconds(5)).requireSuccess("Resolving gid");
            args.addAll(List.of("--user", uid + ":" + gid));
        }
        args.addAll(List.of(image.reference(), "--port", Integer.toString(tunneled ? TUNNEL_PORT : port), "--auth", "on",
            "--telemetry", settings.telemetry() ? "on" : "off"));
        String id = run(args, settings.commandTimeout()).requireSuccess("Creating AWS Emulator container");
        return new Container(id, settings.owner(), settings.fingerprint(), port, home, "created", mode, tunneled);
    }

    public void start(Container container) {
        requireOwner(container);
        run("container", "start", container.id()).requireSuccess("Starting AWS Emulator container");
    }

    public Container find(String nameOrId) {
        String filter = nameOrId.startsWith("gradle-") ? "name=^/" + nameOrId + "$" : "id=" + nameOrId;
        String id = run("container", "ls", "--all", "--filter", filter, "--format", "{{.ID}}")
            .requireSuccess("Finding AWS Emulator container");
        if (id.isBlank()) return null;
        if (id.contains("\n")) throw new GradleException("More than one container matched " + nameOrId);
        String format = "{{.Id}}\n{{index .Config.Labels \"" + OWNER_LABEL + "\"}}\n"
            + "{{index .Config.Labels \"" + CONFIG_LABEL + "\"}}\n"
            + "{{index .Config.Labels \"" + PORT_LABEL + "\"}}\n"
            + "{{index .Config.Labels \"" + HOME_LABEL + "\"}}\n{{.State.Status}}\n"
            + "{{index .Config.Labels \"" + MODE_LABEL + "\"}}\n"
            + "{{index .Config.Labels \"" + TRANSPORT_LABEL + "\"}}";
        String[] values = run("container", "inspect", "--format", format, id)
            .requireSuccess("Inspecting AWS Emulator container").split("\n", -1);
        if (values.length != 7 && values.length != 8) throw new GradleException("Unexpected Docker inspection response for " + nameOrId);
        int port;
        try { port = Integer.parseInt(values[3].trim()); }
        catch (NumberFormatException e) { port = -1; }
        Path home = values[4].isBlank() || values[4].equals("<no value>") ? null : Path.of(values[4]);
        return new Container(values[0].trim(), values[1].trim(), values[2].trim(), port, home, values[5].trim(), values[6].trim(),
            values.length == 8 && values[7].trim().equals("tunnel"));
    }

    public List<Container> ownedContainers() {
        String ids = run("container", "ls", "--all", "--filter", "label=" + OWNER_LABEL + "=" + settings.owner(),
            "--format", "{{.ID}}").requireSuccess("Listing project-owned AWS Emulator containers");
        List<Container> containers = new ArrayList<>();
        for (String id : ids.lines().filter(line -> !line.isBlank()).toList()) {
            Container container = find(id);
            if (container != null) {
                requireOwner(container);
                containers.add(container);
            }
        }
        return containers;
    }

    public void requireOwner(Container container) {
        if (!settings.owner().equals(container.owner()) || container.port() < 1 || container.port() > 65535
                || container.home() == null || !container.home().isAbsolute()) {
            throw new GradleException("Refusing to manage container " + container.id() + ": it is not owned by this project's AWS Emulator plugin.");
        }
    }

    public void remove(Container container) {
        requireOwner(container);
        if (container.running()) run("container", "stop", "--time", "10", container.id()).requireSuccess("Stopping AWS Emulator container");
        run("container", "rm", container.id()).requireSuccess("Removing AWS Emulator container");
    }

    public String logs(String id) {
        return run("container", "logs", "--tail", "100", id).requireSuccess("Reading AWS Emulator logs");
    }

    public void archiveLogs(Container container, Path file) throws IOException {
        requireOwner(container);
        commands.runToFile(command(List.of("container", "logs", "--timestamps", container.id())),
            Duration.ofSeconds(settings.commandTimeout()), file).requireSuccess("Archiving AWS Emulator logs");
    }

    public void verifyTunnelTools(Container container) {
        requireOwner(container);
        run("exec", container.id(), "/bin/bash", "--noprofile", "--norc", "-c",
            "command -v cat >/dev/null && [[ $(help wait) == *-n* ]]")
            .requireSuccess("Checking tunnel tools (TUNNEL images require /bin/bash with wait -n, TCP redirections, and cat)");
    }

    /** Retrieves only the three connection files; no remote path is mounted on the Gradle host. */
    public boolean copyConnectionFiles(Container container, Path directory) throws IOException {
        requireOwner(container);
        String source = "/home/emulator/.aws/emulator/" + TUNNEL_PORT + "/";
        var ready = run("exec", container.id(), "/bin/bash", "--noprofile", "--norc", "-c",
            "test -s " + source + "emulator_credentials && test -s " + source + "ca.pem && test -s " + source + "truststore.p12");
        if (ready.exitCode() == 1) return false;
        ready.requireSuccess("Checking generated emulator connection files");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Refusing a symlink for copied emulator connection files");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        for (String name : List.of("emulator_credentials", "ca.pem", "truststore.p12")) {
            Path temporary = Files.createTempFile(directory, ".connection-", ".tmp");
            try {
                run("cp", container.id() + ":" + source + name, temporary.toString()).requireSuccess("Copying emulator " + name);
                if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid copied emulator " + name);
                if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                }
                Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return true;
    }

    public Process openTunnel(Container container) throws IOException {
        requireOwner(container);
        // Both streams carry opaque TLS bytes. The emulator remains the TLS server.
        String forward = """
            set -e
            exec 3<>/dev/tcp/127.0.0.1/%d
            exec 4<&0
            cat <&3 &
            receiver=$!
            cat <&4 >&3 &
            sender=$!
            trap 'kill "$receiver" "$sender" 2>/dev/null || true' EXIT
            wait -n "$receiver" "$sender" || true
            """.formatted(TUNNEL_PORT);
        return commands.start(command(List.of("exec", "-i", container.id(), "/bin/bash", "--noprofile", "--norc", "-c", forward)));
    }

    private CommandRunner.Result run(String... args) { return run(Arrays.asList(args), settings.commandTimeout()); }

    private CommandRunner.Result run(List<String> args, int timeout) {
        return commands.run(command(args), Duration.ofSeconds(timeout));
    }

    private List<String> command(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(settings.dockerExecutable());
        if (!settings.docker().host().isEmpty()) command.addAll(List.of("--host", settings.docker().host()));
        if (!settings.docker().context().isEmpty()) command.addAll(List.of("--context", settings.docker().context()));
        command.addAll(args);
        return command;
    }

    public record Container(String id, String owner, String config, int port, Path home, String state, String mode, boolean tunneled) {
        public Container(String id, String owner, String config, int port, Path home, String state, String mode) {
            this(id, owner, config, port, home, state, mode, false);
        }
        public boolean running() { return state.equals("running"); }
        public boolean managed() {
            return mode.equals("managed") || ((mode.isBlank() || mode.equals("<no value>"))
                && home != null && home.getFileName().toString().startsWith("managed-"));
        }
    }
}
