package io.github.gregwhitaker.awsemulator.internal.docker;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.api.GradleException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Docker CLI adapter. All commands use argument lists and bounded execution. */
public final class DockerClient {
    public static final String OWNER_LABEL = "io.github.gregwhitaker.aws-emulator.owner";
    public static final String CONFIG_LABEL = "io.github.gregwhitaker.aws-emulator.config";
    public static final String PORT_LABEL = "io.github.gregwhitaker.aws-emulator.port";
    public static final String HOME_LABEL = "io.github.gregwhitaker.aws-emulator.home";
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
        List<String> args = new ArrayList<>(List.of("container", "create", "--name", name,
            "--platform", image.platform(), "--label", OWNER_LABEL + "=" + settings.owner(),
            "--label", CONFIG_LABEL + "=" + settings.fingerprint(), "--label", PORT_LABEL + "=" + port,
            "--label", HOME_LABEL + "=" + home, "--publish", "127.0.0.1:" + port + ":" + port,
            "--memory", settings.memoryLimit(), "--memory-swap", settings.memoryLimit(),
            "--mount", "type=bind,source=" + home + ",target=/home/emulator", "--env", "HOME=/home/emulator"));
        if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            String uid = commands.run(List.of("id", "-u"), Duration.ofSeconds(5)).requireSuccess("Resolving uid");
            String gid = commands.run(List.of("id", "-g"), Duration.ofSeconds(5)).requireSuccess("Resolving gid");
            args.addAll(List.of("--user", uid + ":" + gid));
        }
        args.addAll(List.of(image.reference(), "--port", Integer.toString(port), "--auth", "on",
            "--telemetry", settings.telemetry() ? "on" : "off"));
        String id = run(args, settings.commandTimeout()).requireSuccess("Creating AWS Emulator container");
        return new Container(id, settings.owner(), settings.fingerprint(), port, home, "created");
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
            + "{{index .Config.Labels \"" + HOME_LABEL + "\"}}\n{{.State.Status}}";
        String[] values = run("container", "inspect", "--format", format, id)
            .requireSuccess("Inspecting AWS Emulator container").split("\n", -1);
        if (values.length != 6) throw new GradleException("Unexpected Docker inspection response for " + nameOrId);
        int port;
        try { port = Integer.parseInt(values[3].trim()); }
        catch (NumberFormatException e) { port = -1; }
        Path home = values[4].isBlank() || values[4].equals("<no value>") ? null : Path.of(values[4]);
        return new Container(values[0].trim(), values[1].trim(), values[2].trim(), port, home, values[5].trim());
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

    private CommandRunner.Result run(String... args) { return run(Arrays.asList(args), settings.commandTimeout()); }

    private CommandRunner.Result run(List<String> args, int timeout) {
        List<String> command = new ArrayList<>();
        command.add(settings.dockerExecutable());
        command.addAll(args);
        return commands.run(command, Duration.ofSeconds(timeout));
    }

    public record Container(String id, String owner, String config, int port, Path home, String state) {
        public boolean running() { return state.equals("running"); }
    }
}
