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

import io.github.gregwhitaker.awsemulator.internal.docker.CommandRunner;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

/** A runner with no host workspace mount accesses Docker through a separate TCP endpoint. */
class ContainerizedRuntimeIntegrationTest {
    @TempDir Path directory;
    private final ProcessCommandRunner commands = new ProcessCommandRunner();
    private final String identity = "aws-emulator-ci-" + UUID.randomUUID();
    private final String workspace = "/workspace/" + identity;

    @Test @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void managesDefaultAndNamedSharedRuntimesAcrossDockerAndFilesystemBoundaries() throws Exception {
        Path context = Files.createDirectories(directory.resolve("image"));
        for (String name : List.of("Dockerfile", "docker-proxy.py")) {
            try (var source = getClass().getResourceAsStream("/containerized-ci/" + name)) {
                assertNotNull(source);
                Files.copy(source, context.resolve(name));
            }
        }
        String image = "gradle-aws-emulator-ci:" + EmulatorSettings.hash(Files.readString(context.resolve("Dockerfile")));
        docker("build", "--tag", image, context.toString()).requireSuccess("Building containerized Gradle runner");
        Path project = fixture();
        String proxy = identity + "-docker";
        String client = identity + "-runner";
        docker("network", "create", "--internal", identity).requireSuccess("Creating isolated CI network");
        try {
            docker("create", "--name", proxy, "--network", identity, "--network-alias", "engine",
                "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                "python:3.12-slim", "python", "/docker-proxy.py").requireSuccess("Creating Docker API relay");
            docker("cp", context.resolve("docker-proxy.py").toString(), proxy + ":/docker-proxy.py").requireSuccess("Copying relay");
            docker("start", proxy).requireSuccess("Starting relay");
            docker("run", "--detach", "--name", client, "--network", identity,
                "--workdir", workspace, "--env", "DOCKER_HOST=tcp://unreachable.invalid:1",
                image).requireSuccess("Starting isolated Gradle runner");
            docker("cp", project + "/.", client + ":" + workspace).requireSuccess("Copying fixture without bind mounts");
            docker("exec", client, "docker", "context", "create", "ci", "--docker", "host=tcp://engine:2375")
                .requireSuccess("Creating remote context");

            for (int run = 0; run < 2; run++) {
                String output = build(client).requireSuccess("Running containerized shared runtimes");
                if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
                assertEquals(2, output.split("AWS Emulator ready at", -1).length - 1, output);
                assertEquals(4, output.lines().filter(s -> s.startsWith("VERIFIED:")).count(), output);
                List<String> endpoints = output.lines().filter(s -> s.startsWith("VERIFIED:")).toList();
                assertEquals(2, endpoints.stream().distinct().count(), output);
                assertCleanedUp(client);
            }
            var failed = build(client, "-PfailVerify=true", "--continue");
            assertNotEquals(0, failed.exitCode());
            assertTrue(failed.output().contains("Intentional remote consumer failure"), failed.output());
            assertCleanedUp(client);

            // Explicit --host must override the inherited, deliberately invalid DOCKER_HOST too.
            build(client, "-PdockerHost=tcp://engine:2375").requireSuccess("Selecting remote daemon by host");
            assertCleanedUp(client);
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                // Only remove this test's uniquely identified containers, including failed fixture builds.
                for (String owner : owners()) {
                    var result = docker("ps", "--all", "--quiet", "--filter",
                        "label=io.github.gregwhitaker.aws-emulator.owner=" + owner);
                    if (result.exitCode() == 0) for (String id : result.output().lines().filter(s -> !s.isBlank()).toList()) {
                        docker("rm", "--force", id);
                    }
                }
            } finally {
                try { docker("rm", "--force", "--volumes", client, proxy); }
                finally {
                    try { docker("network", "rm", identity); }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private CommandRunner.Result build(String client, String... options) {
        var args = new ArrayList<>(List.of("exec", client, "gradle", ":first:verifyDefault", ":first:verifyNamed",
            ":second:verifyDefault", ":second:verifyNamed", "--parallel", "--max-workers=4",
            "--configuration-cache", "--configuration-cache-problems=fail", "--console=plain", "--stacktrace",
            "-Dorg.gradle.unsafe.isolated-projects=true"));
        args.addAll(List.of(options));
        return docker(args.toArray(String[]::new));
    }

    private void assertCleanedUp(String client) {
        for (String owner : owners()) assertEquals("", docker("ps", "--all", "--quiet", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + owner).requireSuccess("Checking remote cleanup"));
        assertEquals("", docker("exec", client, "find", workspace, "-type", "d", "-name", "managed-*")
            .requireSuccess("Checking runner filesystem cleanup"));
    }

    private List<String> owners() {
        return List.of(EmulatorSettings.hash(workspace + "/owner"), EmulatorSettings.hash(workspace + "/owner\nacceptance"));
    }

    private Path fixture() throws Exception {
        Path project = Files.createDirectories(directory.resolve("project"));
        Path libraries = Files.createDirectories(project.resolve("plugin-classpath"));
        int index = 0;
        for (var entry : runner(project, "help").getPluginClasspath()) {
            Path source = entry.toPath();
            // The container runs the production classes; TestKit's JaCoCo hooks refer to host paths.
            if (source.toString().replace('\\', '/').contains("/jacoco/") || entry.getName().contains("JaCoCo")
                    || entry.getName().equals("integrationTestJacocoProperties")) continue;
            Path target = libraries.resolve((index++) + ".jar");
            if (Files.isDirectory(source)) {
                try (var jar = new JarOutputStream(Files.newOutputStream(target)); var files = Files.walk(source)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        jar.putNextEntry(new JarEntry(source.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, jar);
                        jar.closeEntry();
                    }
                }
            } else Files.copy(source, target);
        }
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'containerized-ci'\ninclude 'owner', 'first', 'second'\n");
        Files.writeString(project.resolve("build.gradle"), "");
        String classpath = "buildscript { dependencies { classpath fileTree('" + workspace
            + "/plugin-classpath') { include '*.jar' } } }\n";
        Files.createDirectories(project.resolve("owner"));
        Files.writeString(project.resolve("owner/build.gradle"), classpath + """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            import io.github.gregwhitaker.awsemulator.model.DockerTransport
            apply plugin: 'io.github.gregwhitaker.aws-emulator'
            awsEmulator {
                port.set(0)
                docker {
                    transport.set(DockerTransport.TUNNEL)
                    host.set(providers.gradleProperty('dockerHost').orElse(''))
                    context.set(providers.gradleProperty('dockerHost').map { '' }.orElse('ci'))
                }
                runtimes { register('acceptance') }
            }
            ['default', 'acceptance'].each { name ->
                tasks.register('seed' + name, CreateSqsQueueTask) {
                    runtimeName.set(name)
                    queueName.set('remote-' + name)
                }
            }
            """);
        for (String child : List.of("first", "second")) {
            Files.createDirectories(project.resolve(child));
            Files.writeString(project.resolve(child + "/build.gradle"), classpath + """
                import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
                import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory
                import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner
                import java.time.Duration
                apply plugin: 'io.github.gregwhitaker.aws-emulator'
                awsEmulator {
                    dockerExecutable.set('consumer-must-not-own-docker')
                    useSharedRuntime(':owner')
                }
                abstract class VerifyRuntime extends AbstractAwsResourceTask {
                    @Input abstract Property<Boolean> getFailVerify()
                    @TaskAction void verify() {
                        def connection = connection()
                        def client = new AwsClientFactory(connection, 30).sqs()
                        try {
                            def queues = client.listQueues().queueUrls()
                            assert queues.size() == 1
                            assert queues[0].endsWith('/remote-' + runtimeName.get())
                        } finally { client.close() }
                        def commands = new ProcessCommandRunner()
                        def owner = runtime.get().parameters.owner.get()
                        def ids = commands.run(['docker', '--context', 'ci', 'ps', '--quiet', '--filter',
                            'label=io.github.gregwhitaker.aws-emulator.owner=' + owner], Duration.ofSeconds(30))
                            .requireSuccess('Finding owned container')
                        assert !ids.contains('\\n') && !ids.isBlank()
                        def layout = commands.run(['docker', '--context', 'ci', 'inspect', '--format',
                            '{{len .Mounts}}/{{len .HostConfig.PortBindings}}', ids], Duration.ofSeconds(30))
                            .requireSuccess('Checking remote container layout')
                        assert layout == '0/0'
                        assert java.nio.file.Files.isRegularFile(connection.credentials())
                        logger.lifecycle('VERIFIED:' + runtimeName.get() + ':' + connection.endpoint())
                        if (failVerify.get()) throw new GradleException('Intentional remote consumer failure')
                    }
                }
                tasks.register('verifyDefault', VerifyRuntime) {
                    dependsOn(':owner:seeddefault')
                    failVerify.set(providers.gradleProperty('failVerify').map { it.toBoolean() }.orElse(false))
                }
                tasks.register('verifyNamed', VerifyRuntime) {
                    runtimeName.set('acceptance')
                    dependsOn(':owner:seedacceptance')
                    failVerify.set(providers.gradleProperty('failVerify').map { it.toBoolean() }.orElse(false))
                }
                """);
        }
        return project;
    }

    private CommandRunner.Result docker(String... arguments) {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        return commands.run(command, Duration.ofMinutes(10));
    }
}
