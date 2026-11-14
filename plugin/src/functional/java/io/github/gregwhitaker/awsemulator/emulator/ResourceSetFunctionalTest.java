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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSetFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void reusesDefinitionsAcrossManagedConsumersAndScenariosInBothDsls(boolean kotlin, boolean isolated) throws Exception {
        fixture(kotlin);
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        String help = runner(project, "help").build().getOutput();
        assertFalse(help.contains("PROBE:"), help);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test", "otherTest", "resetAwsEmulator", "--parallel").build().getOutput();
            ordered(output, "PROBE:RESET", "PROBE:START", "PROBE:DEVELOPMENT:base", "PROBE:DEVELOPMENT:create",
                "PROBE:DEVELOPMENT:seed", "PROBE:DEVELOPMENT:more");
            ordered(output, "PROBE:MANAGED:base", "PROBE:MANAGED:create", "PROBE:MANAGED:seed", "PROBE:MANAGED:more");
            assertEquals(1, output.split("PROBE:MANAGED:seed", -1).length - 1, output);
            assertFalse(output.contains("PROBE:unused"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void failedProvisioningBlocksAllDependentFixturesWithContinue() throws Exception {
        fixture(false);
        append("""
            tasks.withType(Probe).configureEach {
                if (name.endsWith('_create')) doLast { throw new GradleException('intentional resource failure') }
            }
            """);
        String output = runner(project, "test", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("intentional resource failure"), output);
        assertFalse(output.contains("PROBE:MANAGED:seed"), output);
        assertFalse(output.contains("PROBE:MANAGED:more"), output);
    }

    @Test void rejectsIncludeCyclesAndDuplicateStepsBeforeStartingDocker() throws Exception {
        fixture(false);
        append("awsEmulator.resourceSets.named('base') { include(awsEmulator.resourceSets.named('shared')) }\n");
        String cycle = runner(project, "test").buildAndFail().getOutput();
        assertTrue(cycle.contains("Cyclic AWS Emulator resource set includes"), cycle);
        fixture(false);
        append("awsEmulator.resourceSets.named('shared') { seed('seed', Probe) {} }\n");
        String duplicate = runner(project, "test").buildAndFail().getOutput();
        assertTrue(duplicate.contains("Duplicate step 'seed'"), duplicate);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void repeatedConsumerConfigurationUsesFinalModeForAllDefinitions() throws Exception {
        fixture(false);
        append("awsEmulator.useWith(tasks.named('test'), EmulatorMode.DEVELOPMENT)\n");
        String output = runner(project, "test").build().getOutput();
        ordered(output, "PROBE:DEVELOPMENT:base", "PROBE:DEVELOPMENT:create", "PROBE:DEVELOPMENT:seed");
        assertFalse(output.contains("PROBE:MANAGED"), output);
    }

    @Test void resourceSetsUseTheirConsumersModeWhenTheResourceDefaultIsDevelopment() throws Exception {
        fixture(false);
        append("awsEmulator.defaultMode.set(EmulatorMode.DEVELOPMENT)\n");
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test").build().getOutput();
            ordered(output, "PROBE:MANAGED:base", "PROBE:MANAGED:create", "PROBE:MANAGED:seed");
            assertFalse(output.contains("PROBE:DEVELOPMENT"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    @Test void definitionsCannotOverrideTheirAttachmentsMode() throws Exception {
        fixture(false);
        append("""
            awsEmulator.resourceSets.named('shared') {
                seed('wrongMode', Probe) { mode.set(EmulatorMode.DEVELOPMENT) }
            }
            """);
        String output = runner(project, "test").buildAndFail().getOutput();
        assertTrue(output.contains("cannot be changed"), output);
        assertFalse(output.contains("PROBE:"), output);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preservesProducerDependenciesInOrdinaryGradleSetupNotation(boolean isolated) throws Exception {
        fixture(false);
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        append("""
            abstract class PrepareFile extends DefaultTask {
                @OutputFile abstract RegularFileProperty getDestination()
                @TaskAction void prepare() {
                    destination.get().asFile.text = 'fixture'
                    logger.lifecycle('FILE_PROVIDER_SETUP')
                }
            }
            def prepare = tasks.register('prepareFile', PrepareFile) {
                destination.set(layout.buildDirectory.file('fixture.txt'))
            }
            def additional = tasks.register('additionalFile') {
                doLast { logger.lifecycle('FILE_COLLECTION_SETUP') }
            }
            def files = objects.fileCollection().from('extra.txt').builtBy(additional)
            awsEmulator.useWith(tasks.named('test'), EmulatorMode.MANAGED,
                prepare.flatMap { it.destination }.map { it.asFile }, files)
            """);
        for (int run = 0; run < 2; run++) {
            var result = runner(project, "test").build();
            String output = result.getOutput();
            assertNotNull(result.task(":prepareFile"), output);
            if (run == 0) assertTrue(output.contains("FILE_PROVIDER_SETUP"), output);
            assertTrue(output.contains("FILE_COLLECTION_SETUP"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    private void append(String text) throws Exception {
        Files.writeString(project.resolve("build.gradle"), text, java.nio.file.StandardOpenOption.APPEND);
    }

    private void ordered(String output, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int index = output.indexOf(marker);
            assertTrue(index > previous, "Missing or unordered " + marker + "\n" + output);
            previous = index;
        }
    }

    private void fixture(boolean kotlin) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'resource-sets'\n");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            tasks.withType<Probe>().configureEach {
                actions.clear()
                doLast {
                    val selected = javaClass.getMethod("getMode").invoke(this) as Provider<*>
                    logger.lifecycle("PROBE:${selected.get()}:${name.substringAfterLast('_')}")
                }
            }
            val base = awsEmulator.resourceSets.register("base") {
                provision("base", Probe::class.java) {}
            }
            val shared = awsEmulator.resourceSets.register("shared") {
                include(base)
                seed("seed", Probe::class.java) {}
                provision("create", Probe::class.java) {}
                seed("more", Probe::class.java) {}
            }
            awsEmulator.resourceSets.register("unused") { throw GradleException("Unselected definitions realized") }
            val other = tasks.register<Test>("otherTest")
            awsEmulator {
                dockerExecutable.set("no-docker-during-configuration")
                useWith(tasks.named<Test>("test"), EmulatorMode.MANAGED, shared)
                useWith(other, EmulatorMode.MANAGED, shared)
                scenarios { register("default") { resources(shared) } }
            }
            tasks.named("startAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:START") } }
            tasks.named("restartAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:RESET") } }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            tasks.withType(Probe).configureEach {
                actions.clear()
                doLast { task -> logger.lifecycle("PROBE:${task.mode.get()}:${task.name.tokenize('_').last()}") }
            }
            def base = awsEmulator.resourceSets.register('base') {
                provision('base', Probe) {}
            }
            def shared = awsEmulator.resourceSets.register('shared') {
                include(base)
                seed('seed', Probe) {}
                provision('create', Probe) {}
                seed('more', Probe) {}
            }
            awsEmulator.resourceSets.register('unused') { throw new GradleException('Unselected definitions realized') }
            def other = tasks.register('otherTest', Test)
            awsEmulator {
                dockerExecutable.set('no-docker-during-configuration')
                useWith(tasks.named('test'), EmulatorMode.MANAGED, shared)
                useWith(other, EmulatorMode.MANAGED, shared)
                scenarios { register('default') { resources(shared) } }
            }
            tasks.named('startAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:START') } }
            tasks.named('restartAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:RESET') } }
            """);
    }
}
