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

import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ExternalConnectionFunctionalTest {
    @TempDir Path project;

    @Test
    void incompleteExternalSettingsAreLazyAndLifecycleOperationsNeverInvokeDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'external-lazy'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def queue = tasks.register('createQueue', CreateSqsQueueTask) { queueName.set('external-queue') }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                external { }
                useWith(tasks.named('test'))
                scenarios { register('default') { provision(queue) } }
            }
            """);
        runner(project, "test", "tasks").build();
        assertTrue(runner(project, "test", "tasks").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
        String output = runner(project, "prepareAwsEmulator", "startAwsEmulator", "stopAwsEmulator",
            "restartAwsEmulator", "logsAwsEmulator", "cleanupAwsEmulator", "--continue").buildAndFail().getOutput();
        for (String operation : new String[] {"prepare", "start", "stop", "restart", "logs", "cleanup"}) {
            assertTrue(output.contains("Cannot " + operation + "AwsEmulator when awsEmulator.external is enabled"), output);
        }
        assertFalse(output.contains("Cannot execute"), output);
        String reset = runner(project, "resetAwsEmulator", "--continue").buildAndFail().getOutput();
        assertTrue(reset.contains("Cannot restartAwsEmulator when awsEmulator.external is enabled"), reset);
        assertFalse(reset.contains("> Task :startAwsEmulator"), reset);
        String invalid = runner(project, "createQueue").buildAndFail().getOutput();
        assertTrue(invalid.contains("requires endpoint, credentialsFile, and certificateFile"), invalid);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void kotlinConsumersDiagnosticsAndExportsUseExternalFilesAcrossCacheReuse() throws Exception {
        var identity = TlsFixtures.identity(project, "external");
        TlsFixtures.pem(project.resolve("source ca.pem"), identity);
        credentials("first");
        try (var server = TlsFixtures.server(identity)) {
            server.server().createContext("/__admin/health", exchange -> {
                byte[] response = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            fixture(server.url().replace("127.0.0.1", "localhost"));
            String[] tasks = {"probe", "processProbe", "statusAwsEmulator", "doctorAwsEmulator",
                "exportAwsEmulatorConnection", ":child:statusAwsEmulator"};
            String first = runner(project, tasks).build().getOutput();
            assertTrue(first.contains("healthy"), first);
            assertTrue(Files.readString(project.resolve("probe.txt")).contains("first"));
            assertTrue(Files.readString(project.resolve("process.txt")).contains("first"));
            credentials("rotated");
            String second = runner(project, tasks).build().getOutput();
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertTrue(Files.readString(project.resolve("probe.txt")).contains("rotated"));
            assertTrue(Files.readString(project.resolve("process.txt")).contains("rotated"));
            // The supplied files must still exist after the build service closes.
            assertTrue(Files.isRegularFile(project.resolve("source ca.pem")));
            assertEquals("[default]\naws_access_key_id = rotated\naws_secret_access_key = fixture-secret\n",
                Files.readString(project.resolve("source credentials")));
            TlsFixtures.pem(project.resolve("source ca.pem"), TlsFixtures.identity(project, "replacement"));
            String wrongCertificate = runner(project, tasks).buildAndFail().getOutput();
            assertTrue(wrongCertificate.contains("Reusing configuration cache"), wrongCertificate);
            assertTrue(wrongCertificate.contains("Cannot verify AWS Emulator external TLS"), wrongCertificate);
            TlsFixtures.pem(project.resolve("source ca.pem"), identity);
            Files.writeString(project.resolve("source credentials"), "[default]\nsecret-value-without-an-equals-sign\n");
            String malformed = runner(project, tasks).buildAndFail().getOutput();
            assertTrue(malformed.contains("Reusing configuration cache"), malformed);
            assertTrue(malformed.contains("Cannot read AWS Emulator credentials"), malformed);
            assertFalse(malformed.contains("secret-value-without-an-equals-sign"), malformed);
            assertFalse(malformed.contains("Cannot execute"), malformed);
        }
    }

    private void credentials(String key) throws Exception {
        Files.writeString(project.resolve("source credentials"),
            "[default]\naws_access_key_id = " + key + "\naws_secret_access_key = fixture-secret\n");
    }

    private void fixture(String endpoint) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"external-consumers\"\ninclude(\"child\")\n");
        Files.createDirectories(project.resolve("child"));
        Files.writeString(project.resolve("child/build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-child-docker')
                external { } // The selected owner's connection settings are authoritative.
                useSharedRuntime(':')
            }
            """);
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            val probe = tasks.register<JavaExec>("probe") {
                classpath = sourceSets.main.get().runtimeClasspath
                mainClass.set("Probe")
                args("probe.txt")
            }
            val process = tasks.register<Exec>("processProbe") {
                commandLine(javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile,
                    "src/main/java/Probe.java", "process.txt")
            }
            awsEmulator {
                dockerExecutable.set("must-not-invoke-docker")
                // Unused Docker settings must not be validated on this path.
                platform.set("not-a-docker-platform")
                external {
                    endpoint.set("%s")
                    credentialsFile.set(layout.projectDirectory.file("source credentials"))
                    certificateFile.set(layout.projectDirectory.file("source ca.pem"))
                }
                useWith(probe)
                useWith(process)
            }
            """.formatted(endpoint));
        Path source = project.resolve("src/main/java/Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            import java.nio.file.*;
            import java.net.*;
            import java.net.http.*;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    String endpoint = System.getenv("AWS_ENDPOINT_URL");
                    if (!endpoint.startsWith("https://localhost:")) throw new AssertionError(endpoint);
                    if (!System.getenv("AWS_CA_BUNDLE").endsWith("source ca.pem")) throw new AssertionError("CA path");
                    if (System.getProperty("aws.endpointUrl") != null) {
                        String response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/__admin/health")).build(), HttpResponse.BodyHandlers.ofString()).body();
                        if (!response.equals("ok")) throw new AssertionError("TLS health");
                    }
                    Files.writeString(Path.of(args[0]), Files.readString(Path.of(System.getenv("AWS_SHARED_CREDENTIALS_FILE"))));
                }
            }
            """);
    }
}
