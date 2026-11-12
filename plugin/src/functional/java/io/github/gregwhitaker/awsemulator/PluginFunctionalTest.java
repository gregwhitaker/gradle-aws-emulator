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
        for (String task : new String[] {"prepare", "start", "stop", "restart", "status", "logs"}) {
            assertTrue(output.contains(task + "AwsEmulator"), output);
        }
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
                port.set(0)
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
        assertTrue(runner(temporary, "unsupported").buildAndFail().getOutput().contains("requires a Test or JavaExec task"));
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

}
