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

package io.github.gregwhitaker.awsemulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;

class PluginFunctionalTest {
    @TempDir Path temporary;

    @Test
    void groovyDslRegistersAllTasksWithoutInvokingDockerAndReusesConfigurationCache() throws Exception {
        Path project = temporary.resolve("project with spaces");
        Files.createDirectories(project);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        for (String task : new String[] {"prepare", "start", "stop", "restart", "status", "logs", "doctor", "cleanup"}) {
            assertTrue(output.contains(task + "AwsEmulator"), output);
        }
        assertTrue(output.contains("exportAwsEmulatorConnection"));
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void kotlinDslAndNoSourceTestsDoNotStartAnEmulator() throws Exception {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
        Files.writeString(temporary.resolve("build.gradle.kts"), """
            plugins {
                java
                id("io.github.gregwhitaker.aws-emulator")
            }
            awsEmulator {
                dockerExecutable.set("this-command-does-not-exist")
                useWith(tasks.named<Test>("test"))
            }
            """);
        String output = runner(temporary, "test").build().getOutput();
        assertTrue(output.contains("NO-SOURCE"), output);
        assertTrue(runner(temporary, "test").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(temporary.resolve(".aws-emulator")));
    }

    @Test
    void rejectsUnsupportedConsumersClearly() throws Exception {
        Files.writeString(temporary.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        Files.writeString(temporary.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            def unsupported = tasks.register('unsupported')
            awsEmulator { useWith(unsupported) }
            """);
        assertTrue(runner(temporary, "unsupported").buildAndFail().getOutput().contains("requires a Test, JavaExec, or Exec task"));
    }

    @Test
    void skippedExecConsumersAreLazyAndDoctorReportsMissingDockerWithConfigurationCache() throws Exception {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"external-fixture\"\n");
        Files.writeString(temporary.resolve("build.gradle.kts"), """
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            val probe = tasks.register<Exec>("probe") {
                commandLine("must-not-run")
                onlyIf { false }
            }
            awsEmulator {
                dockerExecutable.set("missing-docker-for-doctor")
                useWith(probe)
            }
            """);
        runner(temporary, "probe").build();
        assertTrue(runner(temporary, "probe").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(temporary.resolve(".aws-emulator")));
        String report = runner(temporary, "doctorAwsEmulator").build().getOutput();
        assertTrue(report.contains("ERROR: Cannot execute 'missing-docker-for-doctor'"), report);
        assertTrue(runner(temporary, "doctorAwsEmulator").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(temporary.resolve(".aws-emulator")));
    }

    @Test
    void independentProjectsCanApplyThePluginWithoutTouchingDocker() throws Exception {
        Files.writeString(temporary.resolve("settings.gradle"), "rootProject.name = 'fixture'\ninclude 'one', 'two'\n");
        Files.writeString(temporary.resolve("build.gradle"), "");
        for (String child : new String[] {"one", "two"}) {
            Files.createDirectory(temporary.resolve(child));
            Files.writeString(temporary.resolve(child + "/build.gradle"), """
                plugins { id 'io.github.gregwhitaker.aws-emulator' }
                awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
                """);
        }
        assertTrue(runner(temporary, ":one:tasks", ":two:tasks", "--parallel").build().getOutput().contains("BUILD SUCCESSFUL"));
        assertFalse(Files.exists(temporary.resolve("one/.aws-emulator")));
        assertFalse(Files.exists(temporary.resolve("two/.aws-emulator")));
    }

    @Test
    void usageAcceptsLazyModesAndSetupProvidersWithoutDuplicateStartupActions() throws Exception {
        Files.writeString(temporary.resolve("settings.gradle"), "rootProject.name = 'usage-fixture'\n");
        Files.writeString(temporary.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def first = tasks.register('firstSetup')
            def second = tasks.register('secondSetup')
            def selected = objects.property(EmulatorMode).convention(EmulatorMode.DEVELOPMENT)
            def tests = tasks.named('test')
            awsEmulator {
                dockerExecutable.set('no-docker-for-no-source-tests')
                useWith(tests, EmulatorMode.MANAGED, first)
            }
            def actionCount = tests.get().actions.size()
            awsEmulator.useWith(tests, selected, second)
            assert tests.get().awsEmulatorMode.get() == EmulatorMode.DEVELOPMENT
            assert tests.get().actions.size() == actionCount
            """);
        String output = runner(temporary, "test").build().getOutput();
        assertTrue(output.contains(":firstSetup"), output);
        assertTrue(output.contains(":secondSetup"), output);
        assertTrue(output.contains("NO-SOURCE"), output);
        assertTrue(runner(temporary, "test").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(temporary.resolve(".aws-emulator")));
    }

}
