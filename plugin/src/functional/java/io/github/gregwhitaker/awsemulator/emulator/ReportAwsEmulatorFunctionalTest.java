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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.isolatedRunner;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ReportAwsEmulatorFunctionalTest {
    @TempDir Path project;

    @Test void registrationIsLazyAndCustomReportsDefaultToManaged() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'reports'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.emulator.ReportAwsEmulatorTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('integration') }
            }
            tasks.register('afterTests', ReportAwsEmulatorTask)
            """);
        String tasks = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(tasks.contains("reportAwsEmulatorIntegration"), tasks);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        String failed = runner(project, "afterTests").buildAndFail().getOutput();
        assertTrue(failed.contains("No managed AWS Emulator is active in this build"), failed);
        assertFalse(failed.contains("Cannot execute"), failed);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void externalNamedAndSharedReportsRerunOnCacheReuseAndWriteIssuesBeforeStrictFailure() throws Exception {
        var identity = TlsFixtures.identity(project, "report");
        TlsFixtures.pem(project.resolve("ca.pem"), identity);
        Files.writeString(project.resolve("credentials"), "[default]\naws_access_key_id = report-key\naws_secret_access_key = report-private-secret\n");
        try (var server = TlsFixtures.server(identity)) {
            server.server().removeContext("/");
            server.server().createContext("/", exchange -> {
                boolean health = exchange.getRequestURI().getPath().equals("/__admin/health");
                byte[] body = (health ? "ok" : "private-service-response").getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(health ? 200 : 501, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"reports\"\ninclude(\"child\")\n");
            Files.createDirectories(project.resolve("child"));
            Files.writeString(project.resolve("child/build.gradle.kts"), """
                plugins { id("io.github.gregwhitaker.aws-emulator") }
                awsEmulator { dockerExecutable.set("must-not-invoke-docker"); useSharedRuntime(":") }
                """);
            Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { id("io.github.gregwhitaker.aws-emulator") }
                awsEmulator {
                    dockerExecutable.set("must-not-invoke-docker")
                    runtimes {
                        register("integration") {
                            startupTimeoutSeconds.set(5)
                            external {
                                endpoint.set("%s")
                                credentialsFile.set(layout.projectDirectory.file("credentials"))
                                certificateFile.set(layout.projectDirectory.file("ca.pem"))
                            }
                        }
                    }
                }
                """.formatted(server.url()));
            String previous = "";
            String[] tasks = {"reportAwsEmulatorIntegration", ":child:reportAwsEmulator", "--runtime", "integration"};
            Path reports = project.resolve("build/reports/aws-emulator/integration/development/reportAwsEmulatorIntegration");
            for (int run = 0; run < 2; run++) {
                var result = isolatedRunner(project, tasks).build();
                assertEquals(TaskOutcome.SUCCESS, result.task(":reportAwsEmulatorIntegration").getOutcome());
                if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                String json = Files.readString(reports.resolve("report.json"));
                assertNotEquals(previous, json);
                previous = json;
                assertFalse(json.contains("report-private-secret"));
                assertFalse(json.contains("private-service-response"));
                var document = new ObjectMapper().readTree(json);
                assertTrue(document.path("runtime").path("external").asBoolean());
                assertEquals("integration", document.path("runtime").path("name").asText());
                assertEquals(5, document.path("services").size());
                for (var section : document.path("services")) {
                    assertEquals("FAILED", section.path("status").asText());
                    assertFalse(section.path("issues").isEmpty());
                }
                assertTrue(Files.readString(reports.resolve("index.html")).contains("Incomplete snapshot"));
                assertTrue(Files.exists(project.resolve("child/build/reports/aws-emulator/integration/development/reportAwsEmulator/report.json")));
            }
            String strict = isolatedRunner(project, "reportAwsEmulatorIntegration", "--fail-on-error").buildAndFail().getOutput();
            assertTrue(strict.contains("AWS Emulator report is incomplete"), strict);
            assertTrue(Files.isRegularFile(reports.resolve("index.html")));
            assertTrue(Files.isRegularFile(reports.resolve("report.json")));
            Files.delete(project.resolve("ca.pem"));
            String unavailable = isolatedRunner(project, "reportAwsEmulatorIntegration").buildAndFail().getOutput();
            assertTrue(unavailable.contains("certificateFile"), unavailable);
            assertFalse(Files.exists(reports.resolve("index.html")));
            assertFalse(Files.exists(reports.resolve("report.json")));
        }
    }
}
