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

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorIntegrationTest {
    @TempDir Path project;

    @Test
    void managedTestsUseSignedHttpsShareOneRuntimeReuseConfigurationAndCleanUpAfterFailure() throws Exception {
        fixture(project);
        var first = runner(project, "test", "secondTest", "execProbe", "processProbe").build();
        assertTrue(first.getOutput().contains("AWS Emulator ready at"), first.getOutput());
        assertEquals(Files.readString(project.resolve("test-connection.txt")), Files.readString(project.resolve("second-connection.txt")));
        assertEquals(Files.readString(project.resolve("test-connection.txt")), Files.readString(project.resolve("exec-connection.txt")));
        assertEquals(Files.readString(project.resolve("test-connection.txt")), Files.readString(project.resolve("process-connection.txt")));
        assertTrue(first.getOutput().contains("container logs saved to"), first.getOutput());
        assertArchivedLogs(project);
        assertCleanedUp(project, "test-connection.txt");
        assertCleanedUp(project, "second-connection.txt");
        var second = runner(project, "test", "secondTest", "execProbe", "processProbe").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertTrue(second.getOutput().contains("AWS Emulator ready at"), second.getOutput());
        assertCleanedUp(project, "test-connection.txt");
        var failed = runner(project, "test", "-PfailFixture=true").buildAndFail();
        assertTrue(failed.getOutput().contains("Intentional test failure"), failed.getOutput());
        assertCleanedUp(project, "test-connection.txt");
        var failedProcess = runner(project, "processProbe", "-PfailProcess=true").buildAndFail();
        assertTrue(failedProcess.getOutput().contains("Intentional process failure"), failedProcess.getOutput());
        assertCleanedUp(project, "process-connection.txt");
        assertArchivedLogs(project);
        assertEquals("", containers(project));
    }

    @Test
    void developmentLifecyclePersistsAcrossBuildsAndManagedCleanupLeavesItRunning() throws Exception {
        fixture(project);
        try {
            assertTrue(runner(project, "prepareAwsEmulator").build().getOutput().contains("image ready"));
            assertTrue(runner(project, "startAwsEmulator").build().getOutput().contains("development instance ready"));
            String original = containers(project);
            assertFalse(original.isBlank());
            assertTrue(runner(project, "startAwsEmulator").build().getOutput().contains("Reusing configuration cache"));
            assertEquals(original, containers(project));
            assertTrue(runner(project, "statusAwsEmulator").build().getOutput().contains("running, healthy"));
            assertTrue(runner(project, "logsAwsEmulator").build().getOutput().contains("emulator server information"));
            String doctor = runner(project, "doctorAwsEmulator").build().getOutput();
            assertTrue(doctor.contains("HTTPS health: healthy"), doctor);
            assertTrue(runner(project, "cleanupAwsEmulator", "--report-only").build().getOutput().contains("Skipped development instance"));
            runner(project, "cleanupAwsEmulator").build();
            assertEquals(original, containers(project));
            runner(project, "exportAwsEmulatorConnection").build();
            assertTrue(Files.readString(project.resolve(".aws-emulator/development/connection.sh")).contains("export AWS_ENDPOINT_URL="));
            assertTrue(Files.readString(project.resolve(".aws-emulator/development/connection.ps1")).contains("$env:NODE_EXTRA_CA_CERTS"));
            assertTrue(runner(project, "exportAwsEmulatorConnection").build().getOutput().contains("Reusing configuration cache"));
            runner(project, "processProbe", "-PdevelopmentProcess=true").build();
            assertTrue(Files.exists(Path.of(Files.readAllLines(project.resolve("process-connection.txt")).get(1))));
            assertEquals(original, containers(project));
            runner(project, "test").build();
            assertEquals(original, containers(project), "Managed cleanup must not stop the development instance");
            runner(project, "restartAwsEmulator").build();
            assertNotEquals(original, containers(project));
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers(project));
        assertFalse(Files.exists(project.resolve(".aws-emulator/development/connection.sh")));
        assertTrue(runner(project, "statusAwsEmulator").build().getOutput().contains("not running"));
        runner(project, "stopAwsEmulator").build();
    }

    @Test
    void cleanupRemovesAbandonedRunningManagedContainersAndRetainsTheirLogs() throws Exception {
        fixture(project);
        String staleId = null;
        try {
            runner(project, "startAwsEmulator").build();
            String development = containers(project);
            String image = docker("container", "inspect", "--format", "{{.Config.Image}}", development);
            String owner = EmulatorSettings.hash(project.toRealPath().toString());
            Path home = project.resolve(".aws-emulator/managed-abandoned");
            Files.createDirectories(home);
            Files.writeString(home.resolve(".gradle-aws-emulator-owner"), owner);
            Path lock = project.resolve(".aws-emulator/locks/managed-abandoned.lock");
            Files.createDirectories(lock.getParent());
            Files.writeString(lock, "");
            staleId = docker("container", "create", "--label", "io.github.gregwhitaker.aws-emulator.owner=" + owner,
                "--label", "io.github.gregwhitaker.aws-emulator.home=" + home,
                "--label", "io.github.gregwhitaker.aws-emulator.port=9297",
                "--label", "io.github.gregwhitaker.aws-emulator.config=abandoned",
                "--label", "io.github.gregwhitaker.aws-emulator.mode=managed",
                "--entrypoint", "/bin/sh", image, "-c", "echo abandoned-fixture; sleep 300");
            docker("container", "start", staleId);
            String preview = runner(project, "cleanupAwsEmulator", "--report-only").build().getOutput();
            assertTrue(preview.contains("Would remove stale managed instance " + staleId), preview);
            assertEquals(2, containers(project).lines().count());
            assertTrue(Files.exists(home));
            String cleanup = runner(project, "cleanupAwsEmulator").build().getOutput();
            assertTrue(cleanup.contains("Removed stale managed instance " + staleId), cleanup);
            assertEquals(development, containers(project));
            assertFalse(Files.exists(home));
            try (var files = Files.list(project.resolve(".aws-emulator/logs"))) {
                assertTrue(Files.readString(files.findFirst().orElseThrow()).contains("abandoned-fixture"));
            }
            assertTrue(runner(project, "cleanupAwsEmulator").build().getOutput().contains("Reusing configuration cache"));
            staleId = null;
        } finally {
            if (staleId != null) new ProcessCommandRunner().run(List.of("docker", "container", "rm", "--force", staleId), Duration.ofSeconds(30));
            runner(project, "stopAwsEmulator").build();
        }
    }

    private static String docker(String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(args));
        return new ProcessCommandRunner().run(command, Duration.ofSeconds(30)).requireSuccess("Docker test fixture");
    }

    @Test
    void parallelProjectsGetSeparateManagedRuntimes() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'parallel-fixture'\ninclude 'one', 'two'\n");
        for (String child : List.of("one", "two")) {
            Path directory = project.resolve(child);
            Files.createDirectories(directory);
            fixture(directory);
        }
        runner(project, ":one:test", ":two:test", "--parallel", "--max-workers=4").build();
        assertNotEquals(Files.readString(project.resolve("one/test-connection.txt")), Files.readString(project.resolve("two/test-connection.txt")));
        for (String child : List.of("one", "two")) {
            assertCleanedUp(project.resolve(child), "test-connection.txt");
            assertEquals("", containers(project.resolve(child)));
        }
    }

    private static void fixture(Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'docker-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins {
                id 'java'
                id 'io.github.gregwhitaker.aws-emulator'
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation platform('org.junit:junit-bom:5.11.4')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                testImplementation platform('software.amazon.awssdk:bom:2.39.6')
                testImplementation 'software.amazon.awssdk:sts'
                testImplementation 'software.amazon.awssdk:url-connection-client'
            }
            def second = tasks.register('secondTest', Test) {
                testClassesDirs = sourceSets.test.output.classesDirs
                classpath = sourceSets.test.runtimeClasspath
                systemProperty 'fixture.output', layout.projectDirectory.file('second-connection.txt').asFile.absolutePath
            }
            tasks.withType(Test).configureEach {
                useJUnitPlatform()
                environment 'AWS_ACCESS_KEY_ID', 'not-real'
                environment 'AWS_SECRET_ACCESS_KEY', 'not-real'
                environment 'AWS_SESSION_TOKEN', 'not-real'
                environment 'AWS_PROFILE', 'production'
                systemProperty 'aws.accessKeyId', 'not-real'
                systemProperty 'aws.secretAccessKey', 'not-real'
                systemProperty 'fixture.fail', providers.gradleProperty('failFixture').getOrElse('false')
                testLogging { exceptionFormat = 'full' }
            }
            test { systemProperty 'fixture.output', layout.projectDirectory.file('test-connection.txt').asFile.absolutePath }
            def execProbe = tasks.register('execProbe', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Probe'
                systemProperty 'fixture.output', layout.projectDirectory.file('exec-connection.txt').asFile.absolutePath
            }
            def javaCommand = javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath
            def processProbe = tasks.register('processProbe', Exec) {
                commandLine javaCommand, layout.projectDirectory.file('ProcessProbe.java').asFile.absolutePath,
                    layout.projectDirectory.file('process-connection.txt').asFile.absolutePath,
                    providers.gradleProperty('failProcess').getOrElse('false')
                environment 'AWS_ACCESS_KEY_ID', 'production'
                environment 'AWS_PROFILE', 'production'
                environment 'AWS_ENDPOINT_URL_STS', 'https://production.invalid'
            }
            awsEmulator {
                useWith(tasks.named('test'))
                useWith(second)
                useWith(execProbe)
                useWith(processProbe, providers.gradleProperty('developmentProcess').isPresent()
                    ? EmulatorMode.DEVELOPMENT : EmulatorMode.MANAGED)
            }
            """);
        Files.writeString(project.resolve("ProcessProbe.java"), """
            import java.nio.file.*;
            import java.net.*;
            import java.net.http.*;
            import java.security.*;
            import java.security.cert.*;
            import javax.net.ssl.*;
            public class ProcessProbe {
                public static void main(String[] args) throws Exception {
                    for (String key : new String[] {"AWS_ACCESS_KEY_ID", "AWS_PROFILE", "AWS_ENDPOINT_URL_STS"}) {
                        if (System.getenv(key) != null) throw new AssertionError("Inherited override: " + key);
                    }
                    if (System.getProperty("aws.endpointUrl") != null) throw new AssertionError("Exec must use its environment");
                    var store = KeyStore.getInstance("PKCS12");
                    store.load(null, null);
                    try (var pem = Files.newInputStream(Path.of(System.getenv("AWS_CA_BUNDLE")))) {
                        store.setCertificateEntry("emulator", CertificateFactory.getInstance("X.509").generateCertificate(pem));
                    }
                    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trust.init(store);
                    var ssl = SSLContext.getInstance("TLS");
                    ssl.init(null, trust.getTrustManagers(), null);
                    String endpoint = System.getenv("AWS_ENDPOINT_URL");
                    var client = HttpClient.newBuilder().sslContext(ssl).build();
                    var response = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/__admin/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || !response.body().trim().equals("ok")) throw new AssertionError(response.body());
                    Files.writeString(Path.of(args[0]), endpoint + "\\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                    if (Boolean.parseBoolean(args[1])) throw new IllegalStateException("Intentional process failure");
                }
            }
            """);
        Path main = project.resolve("src/main/java/Probe.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
            import java.nio.file.*;
            import java.net.*;
            import java.net.http.*;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    String endpoint = System.getProperty("aws.endpointUrl");
                    var response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(endpoint + "/__admin/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || !response.body().trim().equals("ok")) {
                        throw new IllegalStateException("Emulator health check failed");
                    }
                    Files.writeString(Path.of(System.getProperty("fixture.output")),
                        endpoint + "\\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                }
            }
            """);
        Path source = project.resolve("src/test/java/ProbeTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            import org.junit.jupiter.api.Test;
            import java.nio.file.*;
            import software.amazon.awssdk.services.sts.StsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            import static org.junit.jupiter.api.Assertions.*;
            class ProbeTest {
                @Test void talksToEmulator() throws Exception {
                    assertNull(System.getenv("AWS_ACCESS_KEY_ID"));
                    assertNull(System.getenv("AWS_SECRET_ACCESS_KEY"));
                    assertNull(System.getenv("AWS_SESSION_TOKEN"));
                    assertNull(System.getenv("AWS_PROFILE"));
                    assertNull(System.getProperty("aws.accessKeyId"));
                    assertNull(System.getProperty("aws.secretAccessKey"));
                    Files.writeString(Path.of(System.getProperty("fixture.output")),
                        System.getProperty("aws.endpointUrl") + "\\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                    try (StsClient sts = StsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        assertEquals("000000000000", sts.getCallerIdentity().account());
                    }
                    assertFalse(Boolean.getBoolean("fixture.fail"), "Intentional test failure");
                }
            }
            """);
    }

    private static void assertCleanedUp(Path project, String output) throws Exception {
        String credentials = Files.readAllLines(project.resolve(output)).get(1);
        assertFalse(Files.exists(Path.of(credentials)), "Generated credentials should be removed with the managed instance");
    }

    private static void assertArchivedLogs(Path project) throws Exception {
        try (var files = Files.list(project.resolve(".aws-emulator/logs"))) {
            var logs = files.toList();
            assertFalse(logs.isEmpty());
            for (Path log : logs) assertTrue(Files.readString(log).contains("emulator server information"));
        }
    }

    private static String containers(Path project) throws java.io.IOException {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking container cleanup");
    }
}
