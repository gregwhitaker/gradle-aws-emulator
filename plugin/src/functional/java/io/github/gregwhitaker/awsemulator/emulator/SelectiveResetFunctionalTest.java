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
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SelectiveResetFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void cleansDependentsBeforeSharedIncludesAndThenReseeds(boolean kotlin, boolean isolated) throws Exception {
        fixture(kotlin);
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        String help = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(help.contains("cleanAwsEmulatorDemo"), help);
        assertFalse(help.contains("PROBE:"), help);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=RESOURCES", "--parallel").build().getOutput();
            ordered(output, "PROBE:START", "PROBE:drop-app", "PROBE:drop-extra", "PROBE:drop-base",
                "PROBE:create-base", "PROBE:create-app", "PROBE:seed-app");
            assertEquals(1, output.split("PROBE:drop-base", -1).length - 1, output);
            assertFalse(output.contains("PROBE:RESTART"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    @Test void cleanupIsExplicitAndDoesNotRunDuringSeedOrRuntimeReset() throws Exception {
        fixture(false);
        String seed = runner(project, "seedAwsEmulator").build().getOutput();
        assertFalse(seed.contains("PROBE:drop-"), seed);
        String full = runner(project, "resetAwsEmulator").build().getOutput();
        ordered(full, "PROBE:RESTART", "PROBE:START", "PROBE:create-base", "PROBE:seed-app");
        assertFalse(full.contains("PROBE:drop-"), full);
        for (int run = 0; run < 2; run++) {
            String clean = runner(project, "cleanAwsEmulatorDemo").build().getOutput();
            ordered(clean, "PROBE:drop-app", "PROBE:drop-extra", "PROBE:drop-base");
            assertFalse(clean.contains("PROBE:create-"), clean);
            assertFalse(clean.contains("PROBE:seed-"), clean);
            if (run > 0) assertTrue(clean.contains("Reusing configuration cache"), clean);
        }
    }

    @Test void failedCleanupSkipsProvisioningAndFixturesEvenWithContinue() throws Exception {
        fixture(false);
        append("""
            tasks.withType(Probe).configureEach {
                if (name.endsWith('_drop-app')) doLast { throw new GradleException('intentional cleanup failure') }
            }
            """);
        String output = runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=resources", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("intentional cleanup failure"), output);
        for (String absent : new String[] {"PROBE:drop-base", "PROBE:create-base", "PROBE:create-app", "PROBE:seed-app"}) {
            assertFalse(output.contains(absent), output);
        }
    }

    @Test void explicitScenarioCleanupPrecedesSetCleanupAndScopeCanBeConfigured() throws Exception {
        fixture(false);
        append("""
            def clean = tasks.register('explicit-clean', Probe)
            awsEmulator.scenarios.named('demo') {
                cleanup(clean)
                resetScope.set(io.github.gregwhitaker.awsemulator.model.ScenarioResetScope.RESOURCES)
            }
            """);
        String output = runner(project, "resetAwsEmulatorDemo").build().getOutput();
        ordered(output, "PROBE:explicit-clean", "PROBE:drop-app", "PROBE:drop-base", "PROBE:create-base");
        assertFalse(output.contains("PROBE:RESTART"), output);
        String overridden = runner(project, "resetAwsEmulatorDemo", "-PawsEmulator.resetScope=RUNTIME").build().getOutput();
        assertTrue(overridden.contains("PROBE:RESTART"), overridden);
        assertFalse(overridden.contains("PROBE:explicit-clean"), overridden);
    }

    @Test void rejectsMissingCleanupInvalidScopeAndCyclesBeforeLifecycleActions() throws Exception {
        fixture(false);
        append("awsEmulator.scenarios.register('empty') {}\n");
        String absent = runner(project, "resetAwsEmulatorEmpty", "-PawsEmulator.resetScope=RESOURCES").buildAndFail().getOutput();
        assertTrue(absent.contains("has no cleanup steps"), absent);
        assertFalse(absent.contains("PROBE:"), absent);
        String invalid = runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=bogus").buildAndFail().getOutput();
        assertTrue(invalid.contains("must be RUNTIME or RESOURCES"), invalid);
        assertFalse(invalid.contains("PROBE:"), invalid);
        append("awsEmulator.resourceSets.named('base') { include(awsEmulator.resourceSets.named('app')) }\n");
        String cycle = runner(project, "cleanAwsEmulator").buildAndFail().getOutput();
        assertTrue(cycle.contains("Cyclic AWS Emulator resource set includes"), cycle);
        assertFalse(cycle.contains("PROBE:"), cycle);
    }

    @Test void rejectsCleanupDependingOnProvisioning() throws Exception {
        fixture(false);
        append("""
            def shared = tasks.register('shared-task', Probe)
            awsEmulator.scenarios.named('demo') { cleanup(shared); provision(shared) }
            """);
        String output = runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=RESOURCES").buildAndFail().getOutput();
        assertTrue(output.contains("must use separate tasks"), output);
        assertFalse(output.contains("PROBE:"), output);
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void selectsNamedAndExternalRuntimesForCleanup(boolean external) throws Exception {
        fixture(false);
        append("""
            awsEmulator.runtimes.register('integration')
            awsEmulator.scenarios.named('demo') { runtimeName.set('integration') }
            tasks.named('startAwsEmulatorIntegration') { actions.clear(); doLast { logger.lifecycle('PROBE:NAMED-START') } }
            tasks.withType(Probe).configureEach {
                doLast { assert runtimeName.get() == 'integration'; assert mode.get().name() == 'DEVELOPMENT' }
            }
            """);
        if (external) append("""
            awsEmulator.runtimes.named('integration') {
                external {
                    endpoint.set('https://localhost:9443')
                    credentialsFile.set(layout.projectDirectory.file('credentials'))
                    certificateFile.set(layout.projectDirectory.file('ca.pem'))
                }
            }
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "resetAwsEmulator", "-PawsEmulator.resetScope=RESOURCES").build().getOutput();
            ordered(output, "PROBE:drop-app", "PROBE:drop-base", "PROBE:create-base", "PROBE:seed-app");
            assertEquals(!external, output.contains("PROBE:NAMED-START"), output);
            assertFalse(output.contains("PROBE:START"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    private void fixture(boolean kotlin) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'selective-reset'\n");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            tasks.withType<Probe>().configureEach {
                actions.clear()
                doLast { logger.lifecycle("PROBE:${name.substringAfterLast('_')}") }
            }
            val base = awsEmulator.resourceSets.register("base") {
                provision("create-base", Probe::class.java) {}
                cleanup("drop-base", Probe::class.java) {}
            }
            val extra = awsEmulator.resourceSets.register("extra") {
                include(base)
                cleanup("drop-extra", Probe::class.java) {}
            }
            val app = awsEmulator.resourceSets.register("app") {
                include(base, extra)
                provision("create-app", Probe::class.java) {}
                seed("seed-app", Probe::class.java) {}
                cleanup("drop-app", Probe::class.java) {}
            }
            awsEmulator {
                dockerExecutable.set("no-docker-during-configuration")
                defaultScenario.set("demo")
                scenarios { register("demo") { resources(app) } }
            }
            tasks.named("startAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:START") } }
            tasks.named("restartAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:RESTART") } }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            tasks.withType(Probe).configureEach {
                actions.clear()
                doLast { logger.lifecycle("PROBE:${name.substring(name.lastIndexOf('_') + 1)}") }
            }
            def base = awsEmulator.resourceSets.register('base') {
                provision('create-base', Probe) {}
                cleanup('drop-base', Probe) {}
            }
            def extra = awsEmulator.resourceSets.register('extra') {
                include(base)
                cleanup('drop-extra', Probe) {}
            }
            def app = awsEmulator.resourceSets.register('app') {
                include(base, extra)
                provision('create-app', Probe) {}
                seed('seed-app', Probe) {}
                cleanup('drop-app', Probe) {}
            }
            awsEmulator {
                dockerExecutable.set('no-docker-during-configuration')
                defaultScenario.set('demo')
                scenarios { register('demo') { resources(app) } }
            }
            tasks.named('startAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:START') } }
            tasks.named('restartAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:RESTART') } }
            """);
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
}
