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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class NamedRuntimeFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void bothDslsKeepSettingsOwnershipAndExternalConnectionsIndependent(boolean kotlin, boolean isolated) throws Exception {
        settings();
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator {
                dockerExecutable.set("must-not-invoke-docker")
                port.set(9345)
                external { }
                runtimes {
                    register("fast")
                    register("slow") { region.set("us-west-2"); external { } }
                }
                region.set("eu-west-1")
            }
            tasks.register<ListSqsQueuesTask>("fastProbe") { runtimeName.set("fast") }
            tasks.register<ListSqsQueuesTask>("slowProbe") { runtimeName.set("slow") }
            tasks.withType<ListSqsQueuesTask>().configureEach {
                actions.clear()
                doLast {
                    // Avoid TestKit's injected plugin classloader in a cached Kotlin script action.
                    fun value(target: Any, getter: String) = (target.javaClass.getMethod(getter).invoke(target)
                        as org.gradle.api.provider.Provider<*>).get()
                    val service = value(this, "getRuntime")
                    val p = service.javaClass.getMethod("getParameters").invoke(service)
                    logger.lifecycle("PROFILE:" + value(this, "getRuntimeName") + ":" + value(p, "getRegion")
                        + ":" + value(p, "getPort") + ":" + value(p, "getExternalEnabled") + ":" + value(p, "getOwner")
                        + ":" + (value(p, "getWorkingDirectory") as org.gradle.api.file.Directory).asFile)
                }
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                port.set(9345)
                external { }
                runtimes {
                    register('fast')
                    register('slow') { region.set('us-west-2'); external { } }
                }
                region.set('eu-west-1')
            }
            tasks.register('fastProbe', ListSqsQueuesTask) { runtimeName.set('fast') }
            tasks.register('slowProbe', ListSqsQueuesTask) { runtimeName.set('slow') }
            tasks.withType(ListSqsQueuesTask).configureEach {
                actions.clear()
                doLast { task ->
                    def p = task.runtime.get().parameters
                    logger.lifecycle('PROFILE:' + task.runtimeName.get() + ':' + p.region.get() + ':' + p.port.get()
                        + ':' + p.externalEnabled.get() + ':' + p.owner.get() + ':' + p.workingDirectory.get().asFile)
                }
            }
            """);
        String available = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(available.contains("restartAwsEmulatorFast"), available);
        assertTrue(available.contains("exportAwsEmulatorConnectionSlow"), available);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "fastProbe", "slowProbe", "listSqsQueues").build().getOutput();
            assertTrue(output.contains("PROFILE:fast:eu-west-1:0:false:"), output);
            assertTrue(output.contains("PROFILE:slow:us-west-2:0:true:"), output);
            assertTrue(output.contains("PROFILE:default:eu-west-1:9345:true:"), output);
            List<String> profiles = output.lines().filter(line -> line.startsWith("PROFILE:")).toList();
            assertEquals(3, profiles.stream().map(line -> line.split(":")[5]).distinct().count(), output);
            assertTrue(output.replace('\\', '/').contains(".aws-emulator/runtimes/fast"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertTrue(runner(project, "listSqsQueues", "--runtime", "fast").build().getOutput().contains("PROFILE:fast:"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void resourceSetsAndScenariosGetSeparateTasksPerRuntimeAndMode() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.emulator.StartAwsEmulatorTask
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def fast = tasks.register('fastTest', Test)
            def slow = tasks.register('slowTest', Test)
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('fast'); register('slow') }
                def app = resourceSets.register('app') {
                    provision('queue', ListSqsQueuesTask) {
                        actions.clear()
                        doLast { task -> logger.lifecycle('SET:' + task.runtimeName.get() + ':' + task.mode.get()) }
                    }
                }
                useWith(fast, 'fast', app)
                useWith(slow, 'slow', app)
                scenarios {
                    register('fastData') { runtimeName.set('fast'); resources(app) }
                    register('slowData') { runtimeName.set('slow'); resources(app) }
                }
            }
            tasks.withType(StartAwsEmulatorTask).configureEach {
                actions.clear()
                doLast { task -> logger.lifecycle('START:' + task.runtimeName.get()) }
            }
            """);
        String[] tasks = {"fastTest", "slowTest", "seedAwsEmulatorFastData", "seedAwsEmulatorSlowData"};
        for (int run = 0; run < 2; run++) {
            String output = runner(project, tasks).build().getOutput();
            for (String name : List.of("fast", "slow")) {
                assertEquals(1, output.lines().filter(line -> line.equals("SET:" + name + ":MANAGED")).count(), output);
                assertEquals(1, output.lines().filter(line -> line.equals("SET:" + name + ":DEVELOPMENT")).count(), output);
                assertTrue(output.contains("START:" + name), output);
            }
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void finalRuntimeSelectionAppliesToEverySetupAndConflictingTasksFailBeforeDocker() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def probe = tasks.named('test')
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('first'); register('last') }
                def app = resourceSets.register('app') {
                    provision('queue', ListSqsQueuesTask) {
                        actions.clear()
                        doLast { task -> logger.lifecycle('SET:' + task.runtimeName.get()) }
                    }
                }
                useWith(probe, 'first', app)
                useWith(probe, 'last', app)
            }
            """);
        String output = runner(project, "test").build().getOutput();
        assertEquals(1, output.lines().filter(line -> line.equals("SET:last")).count(), output);
        assertFalse(output.contains("SET:first"), output);
        assertTrue(runner(project, "test").build().getOutput().contains("Reusing configuration cache"));
        Files.writeString(project.resolve("build.gradle"), """
            def queue = tasks.register('explicitQueue', io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask) {
                runtimeName.set('first')
            }
            awsEmulator.useWith(tasks.named('test'), 'last', queue)
            """, java.nio.file.StandardOpenOption.APPEND);
        String failure = runner(project, "test").buildAndFail().getOutput();
        assertTrue(failure.contains("uses runtime 'first' but its consumer uses 'last'"), failure);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void sharedOwnerSuppliesNamedRuntimesAndResourceSets() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'named-shared'\ninclude 'app'\n");
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('acceptance') { port.set(12345) } }
                resourceSets.register('app') {
                    provision('queue', ListSqsQueuesTask) {
                        actions.clear()
                        doLast { task -> logger.lifecycle('OWNER:' + task.runtimeName.get() + ':' + task.runtime.get().parameters.port.get()) }
                    }
                }
            }
            """);
        Files.writeString(project.resolve("app/build.gradle"), """
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                useSharedRuntime(':', 'app')
                useWith(tasks.named('test'), 'acceptance')
            }
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, ":app:test").build().getOutput();
            assertTrue(output.contains("OWNER:acceptance:12345"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"queue|false", "queue.map { it }|false", "files().builtBy(queue)|false",
        "files(queue)|false", "files().builtBy(queue).filter { true }|false", "wrapper|false",
        "queue|true", "queue.map { it }|true", "files().builtBy(queue)|true",
        "files(queue)|true", "files().builtBy(queue).filter { true }|true", "wrapper|true"})
    void lazyConsumerSelectionAlignsSetupProducersAndRejectsConflicts(String setup, boolean isolated) throws Exception {
        settings();
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def selected = objects.property(String).convention('first')
            def selectedMode = objects.property(EmulatorMode).convention(EmulatorMode.MANAGED)
            def queue = tasks.register('queue', ListSqsQueuesTask) {
                actions.clear()
                doLast { task ->
                    logger.lifecycle('SETUP:' + task.runtimeName.get() + ':' + task.runtime.get().parameters.region.get())
                }
            }
            def wrapper = tasks.register('prepareWrapped') { inputs.files(queue) }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('first'); register('last') { region.set('eu-west-1') } }
                useWithRuntime(tasks.named('test'), selected, selectedMode, %s)
            }
            selected.set('last')
            """.formatted(setup));
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test").build().getOutput();
            assertTrue(output.contains("SETUP:last:eu-west-1"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        Files.writeString(project.resolve("build.gradle"), "queue.configure { runtimeName.set('first') }\n",
            java.nio.file.StandardOpenOption.APPEND);
        String failure = runner(project, "test").buildAndFail().getOutput();
        assertTrue(failure.contains("uses runtime 'first' but its consumer uses 'last'"), failure);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"startAwsEmulator", "restartAwsEmulator"})
    void scenarioRejectsLifecycleTaskRedirectedToAnotherRuntime(String lifecycle) throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('other') }
                scenarios { register('default') }
            }
            """);
        String scenario = lifecycle.startsWith("restart") ? "resetAwsEmulator" : "seedAwsEmulator";
        String failure = runner(project, lifecycle, "--runtime", "other", scenario).buildAndFail().getOutput();
        assertTrue(failure.contains("uses runtime 'other' but the scenario uses 'default'"), failure);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"register('default')", "register('../escape')", "register('Invalid')",
        "register('foo-bar'); register('fooBar')"})
    void rejectsInvalidOrCollidingRuntimeNames(String declaration) throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { runtimes { %s } }
            """.formatted(declaration));
        String output = runner(project, "help").buildAndFail().getOutput();
        assertTrue(output.contains("reserved") || output.contains("Runtime names") || output.contains("duplicate lifecycle task names"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void rejectsUnknownRuntimeBeforeAnOptedInTaskRuns() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { useWith(tasks.named<Test>("test"), "missing") }
            """);
        String failure = runner(project, "test").buildAndFail().getOutput();
        assertTrue(failure.contains("Unknown AWS Emulator runtime 'missing'"), failure);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void settings() throws Exception { Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'named-fixture'\n"); }
}
