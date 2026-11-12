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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class CognitoExampleIntegrationTest {
    @TempDir Path project;

    @Test void signedJwtExampleReusesConfigurationCacheAndCleansUpAfterSuccessAndFailure() throws Exception {
        fixture();
        runner(project, "test").build();
        String first = Files.readString(project.resolve("build/cognito/managed.json"));
        assertManagedRemoved();
        String repeat = runner(project, "test").build().getOutput();
        assertTrue(repeat.contains("Reusing configuration cache"), repeat);
        assertNotEquals(first, Files.readString(project.resolve("build/cognito/managed.json")), "Fresh managed IDs must replace the prior export");
        assertManagedRemoved();
        String failed = runner(project, "test", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(failed.contains("CognitoIntegrationTest"), failed);
        assertTrue(Files.readString(project.resolve("build/test-results/test/TEST-example.CognitoIntegrationTest.xml"))
            .contains("Intentional example failure to verify managed cleanup"));
        assertManagedRemoved();
        assertEquals("", containers());
    }

    @Test void bootRunAndScenarioSharePersistentResourcesAndSeparateExports() throws Exception {
        fixture();
        try {
            runner(project, "seedAwsEmulator").build();
            String first = Files.readString(project.resolve("build/cognito/development.json"));
            String container = containers();
            assertFalse(container.isBlank());
            runner(project, "bootRunLocal", "-PsmokeBoot=true", "--args=--server.port=0").build();
            runner(project, "bootRunLocal", "-PsmokeBoot=true", "--args=--server.port=0").build();
            String repeat = runner(project, "bootRunLocal", "-PsmokeBoot=true", "--args=--server.port=0").build().getOutput();
            assertTrue(repeat.contains("Reusing configuration cache"), repeat);
            assertEquals(first, Files.readString(project.resolve("build/cognito/development.json")));
            assertEquals(container, containers());
            runner(project, "test").build();
            assertNotEquals(first, Files.readString(project.resolve("build/cognito/managed.json")));
            assertEquals(first, Files.readString(project.resolve("build/cognito/development.json")));
            assertEquals(container, containers());
            assertManagedRemoved();
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertEquals("", containers());
    }

    private void assertManagedRemoved() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("managed-")));
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking Cognito example cleanup");
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("cognito.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle") || relative.startsWith(".aws-emulator")
                        || relative.toString().equals("settings.gradle.kts")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"cognito-example-integration\"\n");
    }
}
