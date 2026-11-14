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
import io.github.gregwhitaker.awsemulator.model.DockerTransport;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DockerClientTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectsTheDaemonForLifecycleFilesLogsAndStreamsWithoutHostMounts(boolean context) throws Exception {
        var commands = new RecordingCommands();
        var options = new DockerOptions(context ? "" : "ssh://builder@example.test", context ? "ci" : "", DockerTransport.TUNNEL);
        var settings = settings(options);
        var client = new DockerClient(settings, commands);
        client.daemonInfo();
        client.imageExists("fixture");
        client.buildImage("fixture", "linux/arm64", directory);
        client.pullImage("fixture", "linux/arm64");
        var container = client.create(settings.developmentName() + "-managed", 12345, directory.resolve("managed"),
            new DockerImageBuilder.Image("fixture", "linux/arm64"));
        client.start(container);
        client.verifyTunnelTools(container);
        assertTrue(client.copyConnectionFiles(container, directory.resolve("connection")));
        client.archiveLogs(container, directory.resolve("logs"));
        assertThrows(IOException.class, () -> client.openTunnel(container));
        client.remove(container);
        for (List<String> command : commands.calls) {
            assertEquals(List.of("docker", context ? "--context" : "--host", context ? "ci" : "ssh://builder@example.test"),
                command.subList(0, 3));
        }
        var create = commands.calls.stream().filter(c -> c.contains("create")).findFirst().orElseThrow();
        assertFalse(create.contains("--mount"));
        assertFalse(create.contains("--publish"));
        assertFalse(create.contains("--user"));
        assertEquals("9297", create.get(create.indexOf("--port") + 1));
        assertTrue(create.contains(DockerClient.PORT_LABEL + "=12345"));
        assertEquals(3, commands.calls.stream().filter(c -> c.get(3).equals("cp")).count());
        assertEquals("fixture", Files.readString(directory.resolve("connection/emulator_credentials")));
        var stream = commands.calls.stream().filter(c -> c.contains("-i")).findFirst().orElseThrow();
        assertFalse(stream.contains("-t"), "A PTY would corrupt TLS bytes");
    }

    @Test
    void validatesDockerSelectionWithoutEchoingPasswords() {
        assertThrows(GradleException.class, () -> new DockerOptions("ssh://builder", "ci", DockerTransport.TUNNEL));
        var error = assertThrows(GradleException.class,
            () -> new DockerOptions("ssh://user:secret@builder", "", DockerTransport.TUNNEL));
        assertFalse(error.getMessage().contains("secret"));
        assertThrows(GradleException.class, () -> new DockerOptions("", "ci\nother", DockerTransport.TUNNEL));
        assertEquals("ssh://user@builder", new DockerOptions("ssh://user@builder", "", DockerTransport.TUNNEL).host());
        assertNotEquals(settings(DockerOptions.DEFAULT).fingerprint(),
            settings(new DockerOptions("", "", DockerTransport.TUNNEL)).fingerprint());
    }

    private EmulatorSettings settings(DockerOptions options) {
        return new EmulatorSettings("owner", "1.0.322", "", "auto", 0, "us-east-1", false,
            "docker", "2g", 5, 5, 10, directory, options);
    }

    private static class RecordingCommands implements CommandRunner {
        private final List<List<String>> calls = new ArrayList<>();
        @Override public Result run(List<String> command, Duration timeout) {
            calls.add(command);
            if (command.get(3).equals("cp")) {
                try { Files.writeString(Path.of(command.get(command.size() - 1)), "fixture"); }
                catch (IOException e) { throw new RuntimeException(e); }
            }
            return new Result(0, "fixture");
        }
        @Override public Process start(List<String> command) throws IOException {
            calls.add(command);
            throw new IOException("fixture");
        }
    }
}
