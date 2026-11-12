package io.github.gregwhitaker.awsemulator.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootPluginFunctionalTest {
    @TempDir Path project;

    @Test
    void companionAppliesCoreWithoutRequiringOrApplyingSpringBoot() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator.spring-boot' }
            awsEmulator { dockerExecutable.set('no-docker-during-configuration') }
            assert plugins.hasPlugin('io.github.gregwhitaker.aws-emulator')
            assert !plugins.hasPlugin('org.springframework.boot')
            assert tasks.findByName('bootRunLocal') == null
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("startAwsEmulator"), output);
        assertFalse(output.contains("bootRunLocal"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest
    @CsvSource({"3.5.16, bootFirst", "3.5.16, companionFirst", "4.1.2, bootFirst", "4.1.2, javaLast"})
    void configuresBothBootVersionsInEitherPluginOrderWithoutRunningOrdinaryBootRun(String version, String order) throws Exception {
        settings();
        String boot = "id 'org.springframework.boot' version '" + version + "'\n";
        String companion = "id 'io.github.gregwhitaker.aws-emulator.spring-boot'\n";
        String plugins = switch (order) {
            case "bootFirst" -> "id 'java'\n" + boot + companion;
            case "companionFirst" -> companion + "id 'java'\n" + boot;
            default -> companion + boot + "id 'java'\n";
        };
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import org.springframework.boot.gradle.tasks.run.BootRun
            plugins {
            %s
            }
            awsEmulator { dockerExecutable.set('no-docker-during-configuration') }
            tasks.named('bootRun') {
                mainClass.set('example.Application')
                jvmArgs '-Doriginal=true'
                args 'original-argument'
                systemProperty 'fixture', 'original'
                environment 'FIXTURE_ENV', 'original'
                optimizedLaunch.set(false)
            }
            def prepare = tasks.register('prepareApplication')
            springBootEmulator {
                mode.set(EmulatorMode.MANAGED)
                profiles.set(['development', 'fixture'])
                jvmArgs.add('-Dadditional=true')
                setupTasks.add(prepare.name)
                debugPort.set(5111)
                debugSuspend.set(false)
            }
            def local = tasks.named('bootRunLocal').get()
            def debug = tasks.named('bootRunLocalDebug').get()
            assert local instanceof BootRun
            assert debug instanceof BootRun
            assert local.mainClass.get() == 'example.Application'
            assert local.classpath.files == tasks.bootRun.classpath.files
            assert local.jvmArgs.contains('-Doriginal=true')
            assert local.jvmArgumentProviders.last().asArguments().toList() ==
                ['-Dadditional=true', '-Dspring.profiles.active=development,fixture']
            assert local.args == ['original-argument']
            assert local.systemProperties.fixture == 'original'
            assert local.environment.FIXTURE_ENV == 'original'
            assert !local.optimizedLaunch.get()
            assert local.awsEmulatorMode.get() == EmulatorMode.MANAGED
            assert local.taskDependencies.getDependencies(local).contains(prepare.get())
            assert debug.taskDependencies.getDependencies(debug).contains(prepare.get())
            assert !local.debugOptions.enabled.get()
            assert debug.debugOptions.enabled.get()
            assert debug.debugOptions.port.get() == 5111
            assert debug.debugOptions.host.get() == '127.0.0.1'
            assert !debug.debugOptions.suspend.get()
            assert !tasks.bootRun.extensions.extraProperties.has('awsEmulatorMode')
            assert !tasks.bootRun.debugOptions.enabled.get()
            assert tasks.bootRun.jvmArgumentProviders.empty
            """.formatted(plugins));
        for (String task : new String[] {"bootRunLocal", "bootRunLocalDebug"}) {
            String output = runner(project, task, "--dry-run").build().getOutput();
            assertTrue(output.contains(":prepareApplication SKIPPED"), output);
            assertTrue(output.indexOf(":prepareApplication SKIPPED") < output.indexOf(":" + task + " SKIPPED"), output);
            assertFalse(output.contains(":bootRun SKIPPED"), output);
            assertTrue(runner(project, task, "--dry-run").build().getOutput().contains("Reusing configuration cache"));
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void kotlinDslSupportsDefaultsAndDisablingTheProfileArgument() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import org.springframework.boot.gradle.tasks.run.BootRun
            plugins {
                java
                id("org.springframework.boot") version "4.1.2"
                id("io.github.gregwhitaker.aws-emulator.spring-boot")
            }
            awsEmulator { dockerExecutable.set("no-docker-during-configuration") }
            check(springBootEmulator.mode.get() == EmulatorMode.DEVELOPMENT)
            check(springBootEmulator.profiles.get() == listOf("local"))
            check(springBootEmulator.debugPort.get() == 5005)
            check(springBootEmulator.debugSuspend.get())
            val prepare = tasks.register("prepareApplication")
            springBootEmulator {
                profiles.set(emptyList())
                setupTasks.add(prepare.name)
            }
            val run = tasks.named<BootRun>("bootRunLocal").get()
            check(run.jvmArgumentProviders.last().asArguments().none())
            """);
        String output = runner(project, "bootRunLocal", "--dry-run").build().getOutput();
        assertTrue(output.contains(":prepareApplication SKIPPED"), output);
        assertTrue(runner(project, "bootRunLocal", "--dry-run").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void settings() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'boot-fixture'\n");
    }
}
