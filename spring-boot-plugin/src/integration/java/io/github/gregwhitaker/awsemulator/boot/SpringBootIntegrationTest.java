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

package io.github.gregwhitaker.awsemulator.boot;

import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import io.github.gregwhitaker.awsemulator.testsupport.ExternalEmulatorFixture;
import io.github.gregwhitaker.awsemulator.internal.runtime.HttpsHealthProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootIntegrationTest {
    @TempDir Path project;

    @Test
    void namedBootRuntimeSelectsItsOwnSetupAndCleansOnlyItsManagedInstance() throws Exception {
        fixture("3.5.16");
        Files.writeString(project.resolve("build.gradle"), """
            awsEmulator {
                runtimes {
                    register('application')
                    register('other') { dockerExecutable.set('must-not-invoke-other-docker') }
                }
                springBoot { runtimeName.set('application') }
            }
            tasks.register('otherQueue', io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask) {
                runtimeName.set('other')
                queueName.set('other-runtime-queue')
            }
            """, StandardOpenOption.APPEND);
        try {
            for (int run = 0; run < 3; run++) {
                String output = runner(project, "bootRunLocal").build().getOutput();
                assertTrue(output.contains("BOOT_EMULATOR_VERIFIED"), output);
                assertFalse(output.contains("> Task :otherQueue"), output);
                if (run > 1) assertTrue(output.contains("Reusing configuration cache"), output);
            }
            var development = Files.readAllLines(project.resolve("connection.txt"));
            Path credentials = Path.of(development.get(1));
            assertTrue(credentials.toString().replace('\\', '/').contains("/runtimes/application/development/"));
            String failed = runner(project, "bootRunLocal", "-Pmanaged=true", "--args=fail").buildAndFail().getOutput();
            assertTrue(failed.contains("Intentional application failure"), failed);
            assertCredentialsRemoved();
            assertTrue(new HttpsHealthProbe().ready(development.get(0), credentials.getParent().resolve("truststore.p12")));
            assertFalse(Files.exists(project.resolve(".aws-emulator/runtimes/other")));
        } finally {
            runner(project, "stopAwsEmulatorApplication").build();
        }
    }

    @Test
    void localBootRunsLeaveExternalEmulatorRunningForBothModesAndOnFailure() throws Exception {
        fixture("3.5.16");
        Files.writeString(project.resolve("build.gradle"), """
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                external {
                    endpoint.set(providers.gradleProperty('externalEndpoint'))
                    credentialsFile.set(layout.projectDirectory.file(providers.gradleProperty('externalCredentials')))
                    certificateFile.set(layout.projectDirectory.file(providers.gradleProperty('externalCertificate')))
                }
            }
            """, StandardOpenOption.APPEND);
        try (var owner = new ExternalEmulatorFixture(project.resolve("external-owner"))) {
            var connection = owner.connection();
            byte[] credentials = Files.readAllBytes(connection.credentials());
            var arguments = new java.util.ArrayList<>(java.util.List.of("bootRunLocal",
                "-PexternalEndpoint=" + connection.endpoint(), "-PexternalCredentials=" + connection.credentials(),
                "-PexternalCertificate=" + connection.certificate()));
            for (int run = 0; run < 3; run++) {
                String output = runner(project, arguments.toArray(String[]::new)).build().getOutput();
                assertTrue(output.contains("BOOT_EMULATOR_VERIFIED"), output);
                if (run > 1) assertTrue(output.contains("Reusing configuration cache"), output);
            }
            arguments.add("-Pmanaged=true");
            runner(project, arguments.toArray(String[]::new)).build();
            arguments.add("--args=fail");
            String failure = runner(project, arguments.toArray(String[]::new)).buildAndFail().getOutput();
            assertTrue(failure.contains("Intentional application failure"), failure);
            assertEquals(connection.endpoint() + "\n" + connection.credentials(), Files.readString(project.resolve("connection.txt")));
            assertTrue(new HttpsHealthProbe().ready(connection.endpoint(), connection.trustStore()));
            assertArrayEquals(credentials, Files.readAllBytes(connection.credentials()));
        }
    }

    @Test
    void boot3SharesProvisioningPersistsDevelopmentAndCleansManagedSuccessAndFailure() throws Exception {
        fixture("3.5.16");
        try {
            String first = runner(project, "bootRunLocal").build().getOutput();
            assertTrue(first.contains("BOOT_EMULATOR_VERIFIED"), first);
            String development = containers();
            assertFalse(development.isBlank());
            String connection = Files.readString(project.resolve("connection.txt"));
            // Boot's automatic main-class discovery observes class directories before the first compilation.
            // Their creation can invalidate its first cache entry; subsequent builds must reuse it.
            runner(project, "bootRunLocal").build();
            String repeat = runner(project, "bootRunLocal").build().getOutput();
            assertTrue(repeat.contains("Reusing configuration cache"), repeat);
            assertTrue(repeat.contains("BOOT_EMULATOR_VERIFIED"), repeat);
            assertEquals(development, containers());
            assertEquals(connection, Files.readString(project.resolve("connection.txt")));
            String managed = runner(project, "bootRunLocal", "-Pmanaged=true").build().getOutput();
            assertTrue(managed.contains("BOOT_EMULATOR_VERIFIED"), managed);
            assertCredentialsRemoved();
            assertEquals(development, containers());
            String failure = runner(project, "bootRunLocal", "-Pmanaged=true", "--args=fail").buildAndFail().getOutput();
            assertTrue(failure.contains("Intentional application failure"), failure);
            assertCredentialsRemoved();
            assertEquals(development, containers());
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test
    void boot4DebugRunsWithManagedCleanupAndConfigurationCacheReuse() throws Exception {
        fixture("4.1.2");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var identity = TlsFixtures.identity(project, "custom-https");
        TlsFixtures.trustStore(project.resolve("custom trust.p12"), "PKCS12", TlsFixtures.PASSWORD, identity);
        try (var https = TlsFixtures.server(identity)) {
            Files.writeString(project.resolve("build.gradle"), """
                awsEmulator.springBoot.jvmArgs.addAll([
                    '-Djavax.net.ssl.trustStore=custom trust.p12',
                    '-Djavax.net.ssl.trustStorePassword=test-password',
                    '-Dfixture.https=%s'
                ])
                """.formatted(https.url()), StandardOpenOption.APPEND);
            for (int run = 0; run < 3; run++) {
                String output = runner(project, "bootRunLocalDebug", "-Pmanaged=true", "-PdebugPort=" + port).build().getOutput();
                assertTrue(output.contains("BOOT_EMULATOR_VERIFIED"), output);
                assertTrue(output.contains("Listening for transport dt_socket at address:"), output);
                if (run > 1) assertTrue(output.contains("Reusing configuration cache"), output);
                assertCredentialsRemoved();
                assertEquals("", containers());
            }
        }
    }

    private void fixture(String bootVersion) throws Exception {
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'boot-runtime-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins {
                id 'java'
                id 'org.springframework.boot' version '%s'
                id 'io.github.gregwhitaker.aws-emulator.spring-boot'
            }
            repositories { mavenCentral() }
            dependencies {
                implementation platform('org.springframework.boot:spring-boot-dependencies:%s')
                implementation 'org.springframework.boot:spring-boot-starter'
                implementation platform('software.amazon.awssdk:bom:2.39.6')
                implementation 'software.amazon.awssdk:sqs'
                implementation 'software.amazon.awssdk:url-connection-client'
            }
            awsEmulator {
                port.set(0)
                springBoot {
                    mode.set(providers.gradleProperty('managed').isPresent() ? EmulatorMode.MANAGED : EmulatorMode.DEVELOPMENT)
                    jvmArgs.add('-Dfixture.additional=true')
                    debugSuspend.set(false)
                    debugPort.set(providers.gradleProperty('debugPort').map { it.toInteger() }.orElse(5005))
                }
            }
            tasks.register('createApplicationQueue', CreateSqsQueueTask) {
                queueName.set('boot-fixture-events')
            }
            tasks.named('bootRun') {
                jvmArgs '-Dfixture.inherited=true'
                environment 'FIXTURE_INHERITED', 'true'
                environment 'AWS_ACCESS_KEY_ID', 'not-real'
                environment 'AWS_SECRET_ACCESS_KEY', 'not-real'
            }
            """.formatted(bootVersion, bootVersion));
        Path source = project.resolve("src/main/java/example/Application.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package example;
            import java.nio.file.*;
            import java.util.*;
            import org.springframework.boot.*;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.core.env.Environment;
            import software.amazon.awssdk.services.sqs.SqsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            @SpringBootApplication
            public class Application implements CommandLineRunner {
                private final Environment environment;
                public Application(Environment environment) { this.environment = environment; }
                public static void main(String[] args) {
                    try (var context = SpringApplication.run(Application.class, args)) { }
                }
                public void run(String... args) throws Exception {
                    if (!Arrays.asList(environment.getActiveProfiles()).contains("local")) throw new AssertionError("profile");
                    if (!Boolean.getBoolean("fixture.inherited") || !Boolean.getBoolean("fixture.additional")) throw new AssertionError("JVM args");
                    if (!"true".equals(System.getenv("FIXTURE_INHERITED"))) throw new AssertionError("environment");
                    if (System.getenv("AWS_ACCESS_KEY_ID") != null) throw new AssertionError("inherited credentials");
                    Files.writeString(Path.of("connection.txt"), System.getProperty("aws.endpointUrl") + "\\n"
                        + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                    try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        String url = sqs.getQueueUrl(r -> r.queueName("boot-fixture-events")).queueUrl();
                        if (!url.contains("boot-fixture-events")) throw new AssertionError("setup queue");
                    }
                    if (System.getProperty("fixture.https") != null) {
                        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(System.getProperty("fixture.https"))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                        if (!response.body().equals("trusted")) throw new AssertionError("custom TLS");
                    }
                    System.out.println("BOOT_EMULATOR_VERIFIED");
                    if (Arrays.asList(args).contains("fail")) throw new IllegalStateException("Intentional application failure");
                }
            }
            """);
    }

    private void assertCredentialsRemoved() throws Exception {
        assertFalse(Files.exists(Path.of(Files.readAllLines(project.resolve("connection.txt")).get(1))));
    }

    private String containers() throws Exception {
        String owner = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(project.toRealPath().toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        Process process = new ProcessBuilder("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + owner, "--format", "{{.ID}}")
            .redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
