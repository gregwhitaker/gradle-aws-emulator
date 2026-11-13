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
import io.github.gregwhitaker.awsemulator.testsupport.ExternalEmulatorFixture;
import io.github.gregwhitaker.awsemulator.internal.runtime.HttpsHealthProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ExternalConnectionIntegrationTest {
    @TempDir Path temporary;

    @Test
    void resourceTasksTestsAndScenariosLeaveExternallyOwnedStateAliveAfterSuccessAndFailure() throws Exception {
        Path project = temporary.resolve("consumer");
        fixture(project);
        try (var owner = new ExternalEmulatorFixture(temporary.resolve("owner"))) {
            var connection = owner.connection();
            byte[] credentials = Files.readAllBytes(connection.credentials());
            byte[] certificate = Files.readAllBytes(connection.certificate());
            var options = List.of("-PemulatorEndpoint=" + connection.endpoint(),
                "-PemulatorCredentialsFile=" + connection.credentials(),
                "-PemulatorCertificateFile=" + connection.certificate());
            try (var sqs = new AwsClientFactory(connection, 30).sqs()) {
                String markerQueue = sqs.createQueue(r -> r.queueName("externally-owned-marker")).queueUrl();
                sqs.sendMessage(r -> r.queueUrl(markerQueue).messageBody("state must survive"));
                for (int run = 0; run < 2; run++) {
                    String output = runner(project, arguments(options, "integrationTest", "listKinesisStreams")).build().getOutput();
                    assertTrue(output.contains("external-example-records"), output);
                    if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
                    assertTrue(new HttpsHealthProbe().ready(connection.endpoint(), connection.trustStore()));
                }
                String seed = runner(project, arguments(options, "seedAwsEmulator")).build().getOutput();
                assertFalse(seed.contains("> Task :startAwsEmulator"), seed);
                String repeatedSeed = runner(project, arguments(options, "seedAwsEmulator")).build().getOutput();
                assertTrue(repeatedSeed.contains("Reusing configuration cache"), repeatedSeed);
                String failure = runner(project, arguments(options, "integrationTest", "-PfailExample=true")).buildAndFail().getOutput();
                assertTrue(failure.contains("There were failing tests"), failure);
                assertTrue(Files.readString(project.resolve("build/test-results/integrationTest/TEST-example.ExternalEmulatorTest.xml"))
                    .contains("Intentional external consumer failure"));
                String blocked = runner(project, arguments(options, "resetAwsEmulator", "stopAwsEmulator",
                    "cleanupAwsEmulator", "--continue")).buildAndFail().getOutput();
                assertTrue(blocked.contains("Cannot restartAwsEmulator when awsEmulator.external is enabled"), blocked);
                assertTrue(new HttpsHealthProbe().ready(connection.endpoint(), connection.trustStore()));
                assertArrayEquals(credentials, Files.readAllBytes(connection.credentials()));
                assertArrayEquals(certificate, Files.readAllBytes(connection.certificate()));
                assertEquals("state must survive", sqs.receiveMessage(r -> r.queueUrl(markerQueue)).messages().get(0).body());
                assertTrue(sqs.getQueueUrl(r -> r.queueName("external-example-events")).queueUrl().endsWith("/external-example-events"));
            }
        }
    }

    private static String[] arguments(List<String> options, String... tasks) {
        var args = new ArrayList<>(List.of(tasks));
        args.addAll(options);
        return args.toArray(String[]::new);
    }

    private void fixture(Path project) throws Exception {
        Path source = Path.of(System.getProperty("external.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (!(relative.startsWith("src") || relative.toString().equals("build.gradle.kts"))) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"external-integration\"\n");
        Files.writeString(project.resolve("build.gradle.kts"),
            "\nawsEmulator { dockerExecutable.set(\"must-not-invoke-docker\") }\n"
                + "tasks.named(\"listKinesisStreams\") { mustRunAfter(\"createExampleStream\") }\n", StandardOpenOption.APPEND);
    }
}
