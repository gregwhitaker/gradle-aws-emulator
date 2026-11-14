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
import java.nio.file.StandardOpenOption;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SharedRuntimeFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void sharesOwnerTasksAndSettingsAcrossModesAndCacheReuse(boolean kotlin, boolean isolated) throws Exception {
        fixture(kotlin);
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        assertFalse(runner(project, "help").build().getOutput().contains("PROBE:"));
        for (int run = 0; run < 2; run++) {
            String output = runner(project, ":api:test", ":worker:test", ":api:development", ":worker:development",
                ":api:statusAwsEmulator", ":worker:statusAwsEmulator", ":isolated:statusAwsEmulator",
                "--parallel", "--max-workers=4").build().getOutput();
            for (String mode : List.of("MANAGED", "DEVELOPMENT")) {
                String prefix = "PROBE::zz-infra:awsEmulatorResources_application_" + mode + "_";
                assertEquals(1, output.split(prefix + "queue", -1).length - 1, output);
                assertEquals(1, output.split(prefix + "seed", -1).length - 1, output);
                assertTrue(output.indexOf(prefix + "queue") < output.indexOf(prefix + "seed"), output);
            }
            for (String consumer : List.of("api", "worker")) {
                assertTrue(output.contains("PROBE::" + consumer + ":statusAwsEmulator:1234"), output);
            }
            assertTrue(output.contains("PROBE::isolated:statusAwsEmulator:0"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        for (String child : List.of("api", "worker", "zz-infra", "isolated")) {
            assertFalse(Files.exists(project.resolve(child + "/.aws-emulator")));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "useSharedRuntime('zz-infra') | requires an absolute project path",
        "useSharedRuntime(':missing') | Unknown AWS Emulator runtime owner project",
        "useSharedRuntime(':api') | cannot share its own runtime",
        "useSharedRuntime(':plain') | must apply 'io.github.gregwhitaker.aws-emulator'",
        "useSharedRuntime(':zz-infra', 'missing') | Unknown AWS Emulator resource set 'missing' in owner :zz-infra",
        "useSharedRuntime(':zz-infra', '') | resource set names must not be blank",
        "useSharedRuntime(':zz-infra'); useSharedRuntime(':isolated') | already selects :zz-infra"
    })
    void rejectsInvalidSelectionsBeforeDocker(String selection, String message) throws Exception {
        fixture(false);
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve("api/build.gradle"), """
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { useWith(tasks.named('test')); %s }
            """.formatted(selection));
        String output = runner(project, ":api:test").buildAndFail().getOutput();
        assertTrue(output.contains(message), output);
        assertFalse(Files.exists(project.resolve("zz-infra/.aws-emulator")));
    }

    @Test void rejectsCyclicAndChainedOwners() throws Exception {
        fixture(false);
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve("zz-infra/build.gradle"), "\nawsEmulator.useSharedRuntime(':api')\n", StandardOpenOption.APPEND);
        String cycle = runner(project, ":api:test").buildAndFail().getOutput();
        assertTrue(cycle.contains("sharing cannot be chained or cyclic"), cycle);
        fixture(false);
        Files.writeString(project.resolve("zz-infra/build.gradle"), "\nawsEmulator.useSharedRuntime(':isolated')\n", StandardOpenOption.APPEND);
        String chain = runner(project, ":api:test").buildAndFail().getOutput();
        assertTrue(chain.contains("sharing cannot be chained or cyclic"), chain);
    }

    @Test void canShareOnlyRuntimeAndAddResourceSelectionsAfterConsumerRealization() throws Exception {
        fixture(false);
        Files.writeString(project.resolve("api/build.gradle"), """
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator.useSharedRuntime(':zz-infra')
            awsEmulator.useWith(tasks.named('test'))
            tasks.named('test').get()
            if (providers.gradleProperty('withResources').isPresent()) {
                awsEmulator.useSharedRuntime(':zz-infra', 'application', 'application')
            }
            """);
        assertFalse(runner(project, ":api:test").build().getOutput().contains("PROBE:"));
        String output = runner(project, ":api:test", "-PwithResources").build().getOutput();
        assertEquals(1, output.split("PROBE::zz-infra:awsEmulatorResources_application_MANAGED_queue", -1).length - 1, output);
    }

    private void fixture(boolean kotlin) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "include 'api', 'worker', 'zz-infra', 'isolated', 'plain'\n");
        for (String name : List.of("api", "worker", "zz-infra", "isolated", "plain")) Files.createDirectories(project.resolve(name));
        String suffix = kotlin ? ".gradle.kts" : ".gradle";
        Files.writeString(project.resolve("zz-infra/build" + suffix), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator {
                port.set(1234)
                dockerExecutable.set("no-docker-during-configuration")
                resourceSets.register("application") {
                    provision("queue", Probe::class.java) {}
                    seed("seed", Probe::class.java) {}
                }
                resourceSets.register("unused") { throw GradleException("Unused set realized") }
            }
            tasks.withType<Probe>().configureEach {
                actions.clear()
                doLast { logger.lifecycle("PROBE:$path") }
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask as Probe
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                port.set(1234)
                dockerExecutable.set('no-docker-during-configuration')
                resourceSets.register('application') {
                    provision('queue', Probe) {}
                    seed('seed', Probe) {}
                }
                resourceSets.register('unused') { throw new GradleException('Unused set realized') }
            }
            tasks.withType(Probe).configureEach {
                actions.clear()
                doLast { task -> logger.lifecycle("PROBE:${task.path}") }
            }
            """);
        for (String name : List.of("api", "worker", "isolated")) {
            String selection = name.equals("isolated") ? "" : "awsEmulator.useSharedRuntime(\":zz-infra\", \"application\")";
            Files.writeString(project.resolve(name + "/build" + suffix), kotlin ? """
                import io.github.gregwhitaker.awsemulator.emulator.StatusAwsEmulatorTask
                import io.github.gregwhitaker.awsemulator.model.EmulatorMode
                plugins { java; id("io.github.gregwhitaker.aws-emulator") }
                awsEmulator.useWith(tasks.named<Test>("test"))
                awsEmulator.useWith(tasks.register<Test>("development"), EmulatorMode.DEVELOPMENT)
                tasks.named<Test>("test").get()
                val status = tasks.named<StatusAwsEmulatorTask>("statusAwsEmulator") {
                    actions.clear()
                    doLast {
                        val runtime = javaClass.getMethod("getRuntime").invoke(this) as Provider<*>
                        val service = runtime.get()!!
                        val parameters = service.javaClass.getMethod("getParameters").invoke(service)
                        val port = parameters.javaClass.getMethod("getPort").invoke(parameters) as Provider<*>
                        logger.lifecycle("PROBE:$path:${port.get()}")
                    }
                }
                status.get()
                %s
                """.formatted(selection) : """
                import io.github.gregwhitaker.awsemulator.model.EmulatorMode
                plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
                awsEmulator.useWith(tasks.named('test'))
                awsEmulator.useWith(tasks.register('development', Test), EmulatorMode.DEVELOPMENT)
                tasks.named('test').get()
                tasks.named('statusAwsEmulator') {
                    actions.clear()
                    doLast { task -> logger.lifecycle("PROBE:${task.path}:${task.runtime.get().parameters.port.get()}") }
                }.get()
                %s
                """.formatted(selection));
        }
    }
}
