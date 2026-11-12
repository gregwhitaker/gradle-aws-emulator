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

import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SpringCloudAwsIntegrationTest {
    @TempDir Path project;
    private static final String VERIFIED = "SPRING_CLOUD_AWS_VERIFIED";

    @Test void frameworkPathsReuseDefinitionsForManagedTestsAndPersistentBootWithConfigurationCache() throws Exception {
        fixture();
        try {
            String development = null;
            String connection = null;
            for (int run = 0; run < 2; run++) {
                var result = runner(project, "test", "bootRunLocal").build();
                assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
                assertTrue(result.getOutput().contains(VERIFIED), result.getOutput());
                assertTrue(Files.readString(project.resolve("build/test-results/test/TEST-example.ApplicationTest.xml")).contains(VERIFIED));
                assertManagedRemoved();
                if (run == 0) {
                    development = containers();
                    connection = Files.readString(project.resolve("build/boot-connection.txt"));
                    assertFalse(development.isBlank());
                } else {
                    assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                    assertEquals(development, containers());
                    assertEquals(connection, Files.readString(project.resolve("build/boot-connection.txt")));
                }
            }
            String failure = runner(project, "test", "-PfailExample=true").buildAndFail().getOutput();
            assertTrue(Files.readString(project.resolve("build/test-results/test/TEST-example.ApplicationTest.xml"))
                .contains("Intentional test failure"), failure);
            assertManagedRemoved();
            assertEquals(development, containers());
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test void managedBootCleansUpAfterSuccessApplicationFailureAndMissingRequiredImports() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "bootRunLocal", "-Pmanaged=true").build().getOutput();
            assertTrue(output.contains(VERIFIED), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            assertManagedRemoved();
            assertEquals("", containers());
        }
        String failure = runner(project, "bootRunLocal", "-Pmanaged=true", "--args=fail").buildAndFail().getOutput();
        assertTrue(failure.contains(VERIFIED), failure);
        assertTrue(failure.contains("Intentional application failure"), failure);
        assertManagedRemoved();
        assertEquals("", containers());
        Files.writeString(project.resolve("build.gradle.kts"), """

            awsEmulator.springBoot.resourceSets.set(emptyList())
            awsEmulator.springBoot.autoCreateResources.set(false)
            """, java.nio.file.StandardOpenOption.APPEND);
        String missing = runner(project, "bootRunLocal", "-Pmanaged=true").buildAndFail().getOutput();
        assertTrue(missing.contains("does not exist"), missing);
        assertFalse(missing.contains(VERIFIED), missing);
        assertManagedRemoved();
        assertEquals("", containers());
    }

    private void assertManagedRemoved() throws Exception {
        try (var homes = Files.list(project.resolve(".aws-emulator"))) {
            assertFalse(homes.anyMatch(path -> path.getFileName().toString().startsWith("managed-")),
                "Managed runtime homes and connection files must be removed");
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking Spring Cloud AWS cleanup");
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("springCloudAws.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (!(relative.startsWith("src") || relative.startsWith("fixtures") || relative.toString().equals("build.gradle.kts"))) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"spring-cloud-aws-integration\"\n");
        // Boot observes class directories during configuration; establish them before asserting cache reuse.
        runner(project, "testClasses").build();
    }
}
