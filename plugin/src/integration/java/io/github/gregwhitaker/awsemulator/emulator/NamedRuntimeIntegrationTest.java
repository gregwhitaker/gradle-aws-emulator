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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.HttpsHealthProbe;
import io.github.gregwhitaker.awsemulator.testsupport.ExternalEmulatorFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class NamedRuntimeIntegrationTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void managedSuitesAreIsolatedAndExternalStateSurvivesSuccessFailureAndCacheReuse(boolean tunnel) throws Exception {
        Path project = fixture();
        if (tunnel) Files.writeString(project.resolve("build.gradle.kts"),
            "\nawsEmulator.docker.transport.set(io.github.gregwhitaker.awsemulator.model.DockerTransport.TUNNEL)\n",
            StandardOpenOption.APPEND);
        // Named managed runtimes must not inherit this incomplete external default.
        Files.writeString(project.resolve("build.gradle.kts"), "\nawsEmulator { external { } }\n", StandardOpenOption.APPEND);
        try (var owner = new ExternalEmulatorFixture(temporary.resolve("external-owner"))) {
            var connection = owner.connection();
            var options = List.of("-PremoteEndpoint=" + connection.endpoint(),
                "-PremoteCredentialsFile=" + connection.credentials(), "-PremoteCertificateFile=" + connection.certificate());
            byte[] credentials = Files.readAllBytes(connection.credentials());
            for (int run = 0; run < 2; run++) {
                String output = runner(project, arguments(options, "integrationTest", "acceptanceTest", "remoteTest",
                    "--parallel", "--max-workers=4")).build().getOutput();
                if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
                verifyConnections(project, connection.endpoint());
            }
            String failure = runner(project, arguments(options, "integrationTest", "acceptanceTest", "remoteTest",
                "-PfailSuite=acceptance", "--continue")).buildAndFail().getOutput();
            assertTrue(failure.contains("There were failing tests"), failure);
            assertTrue(Files.readString(project.resolve("build/test-results/acceptanceTest/TEST-example.NamedRuntimeTest.xml"))
                .contains("Intentional named runtime failure"));
            verifyConnections(project, connection.endpoint());
            assertArrayEquals(credentials, Files.readAllBytes(connection.credentials()));
            assertTrue(new HttpsHealthProbe().ready(connection.endpoint(), connection.trustStore()));
        }
    }

    @Test
    void lifecycleCommandsAndScenariosAffectOnlyTheSelectedDevelopmentRuntime() throws Exception {
        Path project = fixture();
        try {
            for (int run = 0; run < 2; run++) {
                String output = runner(project, "seedAwsEmulatorIntegration", "seedAwsEmulatorAcceptance").build().getOutput();
                if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            }
            var integration = development(project, "integration", "us-east-1");
            var acceptance = development(project, "acceptance", "eu-west-1");
            assertNotEquals(integration.endpoint(), acceptance.endpoint());
            try (var first = new AwsClientFactory(integration, 30).sqs(); var second = new AwsClientFactory(acceptance, 30).sqs()) {
                first.createQueue(r -> r.queueName("integration-marker"));
                second.createQueue(r -> r.queueName("acceptance-marker"));
            }
            runner(project, "resetAwsEmulatorIntegration").build();
            var replacement = development(project, "integration", "us-east-1");
            try (var first = new AwsClientFactory(replacement, 30).sqs(); var second = new AwsClientFactory(acceptance, 30).sqs()) {
                assertTrue(first.listQueues().queueUrls().stream().noneMatch(url -> url.endsWith("/integration-marker")));
                assertTrue(second.getQueueUrl(r -> r.queueName("acceptance-marker")).queueUrl().endsWith("/acceptance-marker"));
            }
            assertTrue(runner(project, "statusAwsEmulator", "--runtime", "acceptance").build().getOutput().contains(acceptance.endpoint()));
            runner(project, "stopAwsEmulator", "--runtime", "integration").build();
            assertFalse(Files.exists(replacement.credentials()));
            assertTrue(new HttpsHealthProbe().ready(acceptance.endpoint(), acceptance.trustStore()));
            runner(project, "cleanupAwsEmulatorIntegration").build();
            assertTrue(new HttpsHealthProbe().ready(acceptance.endpoint(), acceptance.trustStore()));
        } finally {
            runner(project, "stopAwsEmulatorIntegration", "stopAwsEmulatorAcceptance").build();
        }
    }

    private void verifyConnections(Path project, String externalEndpoint) throws Exception {
        var endpoints = new java.util.HashSet<String>();
        for (String name : List.of("integration", "acceptance", "remote")) {
            var record = Files.readAllLines(project.resolve("build/connections/" + name + ".txt"));
            assertTrue(endpoints.add(record.get(0)), "Each runtime must have an independent endpoint");
            Path credentials = Path.of(record.get(1));
            if (name.equals("remote")) {
                assertEquals(externalEndpoint, record.get(0));
                assertTrue(Files.isRegularFile(credentials));
            } else {
                assertTrue(credentials.toString().replace('\\', '/').contains("/runtimes/" + name + "/managed-"));
                assertFalse(Files.exists(credentials), "Managed runtime must be cleaned up: " + name);
            }
        }
    }

    private EmulatorConnection development(Path project, String name, String region) throws Exception {
        Path home = project.resolve(".aws-emulator/runtimes/" + name + "/development/.aws/emulator");
        try (var directories = Files.list(home)) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), region, directory);
        }
    }

    private Path fixture() throws Exception {
        Path project = temporary.resolve("consumer");
        Path source = Path.of(System.getProperty("namedRuntimes.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (!(relative.startsWith("src") || relative.toString().equals("build.gradle.kts"))) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"named-runtime-integration\"\n");
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        return project;
    }

    private static String[] arguments(List<String> options, String... tasks) {
        var args = new ArrayList<>(List.of(tasks));
        args.addAll(options);
        return args.toArray(String[]::new);
    }
}
