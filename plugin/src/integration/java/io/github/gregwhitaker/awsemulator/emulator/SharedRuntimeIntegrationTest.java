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

import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SharedRuntimeIntegrationTest {
    @TempDir Path project;
    private String runtimeName = "default";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharesQueuesAcrossProcessesAndCleansUpAfterConsumersAndSetupFail(boolean named) throws Exception {
        fixture(named);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "verifySharedRuntime", "--parallel", "--max-workers=4").build().getOutput();
            assertTrue(output.contains("Processed order-1001 from the other module"), output);
            String endpoint = endpoint(output, "orders-api:run");
            for (String task : List.of("orders-worker:run", "orders-api:test", "orders-worker:test")) {
                assertEquals(endpoint, endpoint(output, task), output);
            }
            assertEquals(1, output.split("AWS Emulator ready at", -1).length - 1, output);
            String prefix = named ? runtimeName + "_" : "";
            assertTrue(output.contains(":local-aws:awsEmulatorResources_" + prefix + "application_MANAGED_ordersQueue"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            assertCleanedUp();
            assertEquals("", containers("local-aws"));
        }

        String failure = runner(project, ":orders-worker:test", ":orders-worker:run", "--continue", "--parallel",
            "-PfailExample=true", "-PfailTest=true").buildAndFail().getOutput();
        assertTrue(failure.contains("Intentional worker failure"), failure);
        assertTrue(failure.contains("Processed order-1001 from the other module"), failure);
        assertTrue(Files.readString(project.resolve("orders-worker/build/test-results/test/TEST-example.orders.WorkerTest.xml"))
            .contains("Intentional worker test failure"));
        assertCleanedUp();
        assertEquals("", containers("local-aws"));

        Files.writeString(project.resolve("local-aws/build.gradle.kts"), """

            tasks.withType<CreateSqsQueueTask>().configureEach {
                doLast { throw GradleException("Intentional shared setup failure") }
            }
            """, StandardOpenOption.APPEND);
        String setup = runner(project, "verifySharedRuntime", "--continue", "--parallel").buildAndFail().getOutput();
        assertTrue(setup.contains("Intentional shared setup failure"), setup);
        assertFalse(setup.contains("AWS Emulator for :orders-"), setup);
        assertCleanedUp();
        assertEquals("", containers("local-aws"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharesDevelopmentAcrossInvocationsWhileManagedTestsStaySeparate(boolean named) throws Exception {
        fixture(named);
        try {
            String first = runner(project, ":orders-worker:run", "-Pdevelopment=true").build().getOutput();
            String original = containers("local-aws");
            assertFalse(original.isBlank(), first);
            assertEquals(endpoint(first, "orders-api:run"), endpoint(first, "orders-worker:run"));
            String second = runner(project, ":orders-worker:run", "-Pdevelopment=true").build().getOutput();
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertEquals(original, containers("local-aws"));
            assertEquals(endpoint(first, "orders-api:run"), endpoint(second, "orders-api:run"));

            String mixed = runner(project, "verifySharedRuntime", "-Pdevelopment=true", "--parallel").build().getOutput();
            assertEquals(endpoint(first, "orders-api:run"), endpoint(mixed, "orders-worker:run"));
            assertEquals(endpoint(mixed, "orders-api:test"), endpoint(mixed, "orders-worker:test"));
            assertNotEquals(endpoint(mixed, "orders-api:test"), endpoint(mixed, "orders-api:run"));
            assertEquals(original, containers("local-aws"));
            assertCleanedUp();
        } finally {
            // A consumer's named lifecycle alias must operate on the owner's instance.
            runner(project, named ? ":orders-worker:stopAwsEmulatorAcceptance" : ":local-aws:stopAwsEmulator").build();
        }
        assertEquals("", containers("local-aws"));
        assertFalse(Files.exists(runtimeDirectory().resolve("development")));
    }

    private void fixture(boolean named) throws Exception {
        Path source = Path.of(System.getProperty("multiProject.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                boolean generated = false;
                for (Path part : relative) if (List.of("build", ".gradle", ".aws-emulator").contains(part.toString())) generated = true;
                if (generated || file.getFileName().toString().equals("README.md")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Path settings = project.resolve("settings.gradle.kts");
        Files.writeString(settings, Files.readString(settings).replace("    includeBuild(\"../..\")", ""));
        Files.writeString(project.resolve("gradle.properties"), "\norg.gradle.unsafe.isolated-projects=true\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (named) {
            runtimeName = "acceptance";
            Files.writeString(project.resolve("local-aws/build.gradle.kts"),
                "\nawsEmulator.runtimes.register(\"acceptance\")\n", StandardOpenOption.APPEND);
            for (String child : List.of("orders-api", "orders-worker")) {
                Files.writeString(project.resolve(child + "/build.gradle.kts"), """

                    awsEmulator {
                        useWithRuntime(tasks.named<JavaExec>("run"), providers.provider { "acceptance" }, applicationMode)
                        useWith(tasks.named<Test>("test"), "acceptance")
                    }
                    """, StandardOpenOption.APPEND);
            }
        }
    }

    private String endpoint(String output, String task) {
        var matcher = Pattern.compile("AWS Emulator for :" + Pattern.quote(task) + ": (https://127\\.0\\.0\\.1:\\d+)").matcher(output);
        assertTrue(matcher.find(), output);
        return matcher.group(1);
    }

    private void assertCleanedUp() throws Exception {
        try (var files = Files.list(runtimeDirectory())) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
        }
        for (String child : List.of("orders-api", "orders-worker")) {
            assertFalse(Files.exists(project.resolve(child + "/.aws-emulator")));
            assertEquals("", containers(child));
        }
    }

    private Path runtimeDirectory() {
        Path directory = project.resolve("local-aws/.aws-emulator");
        return runtimeName.equals("default") ? directory : directory.resolve("runtimes/" + runtimeName);
    }

    private String containers(String child) throws Exception {
        String identity = project.resolve(child).toRealPath().toString();
        if (child.equals("local-aws") && !runtimeName.equals("default")) identity += "\n" + runtimeName;
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(identity),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking shared runtime cleanup");
    }
}
