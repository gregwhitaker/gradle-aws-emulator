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
import java.nio.file.StandardOpenOption;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SetupModeFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "queue|Test|MANAGED|DEVELOPMENT|default|false",
        "queue.get()|JavaExec|DEVELOPMENT|MANAGED|acceptance|true",
        "'queue'|Exec|MANAGED|DEVELOPMENT|default|true",
        "':queue'|Test|DEVELOPMENT|MANAGED|acceptance|false",
        "providers.provider { [queue] }|Test|MANAGED|DEVELOPMENT|acceptance|true",
        "queue.map { it }|Test|DEVELOPMENT|MANAGED|default|true",
        "queue.flatMap { it.outputFile }.map { if (!it.asFile.exists()) throw new GradleException('OUTPUT_READ_EARLY'); it.asFile }|Test|MANAGED|DEVELOPMENT|acceptance|true",
        "files().builtBy(queue)|Test|DEVELOPMENT|MANAGED|default|false",
        "files(queue)|Test|MANAGED|DEVELOPMENT|acceptance|true",
        "wrapper|Test|DEVELOPMENT|MANAGED|acceptance|true"
    })
    void rejectsMismatchedSetupBeforeExecutingAnyTasks(String setup, String consumerType, String consumerMode,
            String setupMode, String runtime, boolean isolated) throws Exception {
        fixture(isolated);
        append("""
            queue.configure { mode.set(EmulatorMode.%s) }
            def consumer = tasks.register('consumer', %s) { onlyIf { false } }
            awsEmulator.useWith(consumer, '%s', EmulatorMode.%s, %s)
            """.formatted(setupMode, consumerType, runtime, consumerMode, setup));
        String output = runner(project, "consumer").buildAndFail().getOutput();
        assertTrue(output.contains("AWS Emulator setup task :queue uses " + setupMode
            + " but consumer :consumer uses " + consumerMode), output);
        assertTrue(output.contains("reusable resource set"), output);
        assertFalse(output.contains("SETUP_RAN:"), output);
        assertFalse(output.contains("OUTPUT_READ_EARLY"), output);
        assertFalse(Files.exists(project.resolve("build/fixture.txt")));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest
    @CsvSource({"MANAGED,false", "DEVELOPMENT,false", "MANAGED,true", "DEVELOPMENT,true"})
    void matchingFinalLazyModesRunOnCacheReuseWithoutRealizingUnusedSetup(String mode, boolean isolated) throws Exception {
        fixture(isolated);
        append("""
            def selectedMode = objects.property(EmulatorMode).convention(EmulatorMode.MANAGED)
            queue.configure { mode.set(selectedMode) }
            def consumer = tasks.named('test')
            awsEmulator.useWith(consumer, 'acceptance', EmulatorMode.MANAGED, queue)
            awsEmulator.useWithRuntime(consumer, providers.provider { 'acceptance' }, selectedMode,
                queue.flatMap { it.outputFile }.map {
                    if (!it.asFile.exists()) throw new GradleException('OUTPUT_READ_EARLY')
                    it.asFile
                }, providers.provider { [] })
            selectedMode.set(EmulatorMode.%s)
            tasks.register('unused', Probe) { throw new GradleException('UNUSED_SETUP_REALIZED') }
            awsEmulator.useWith(tasks.register('unusedTest', Test), EmulatorMode.MANAGED, tasks.named('unused'))
            """.formatted(mode));
        assertFalse(runner(project, "help").build().getOutput().contains("SETUP_RAN:"));
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test").build().getOutput();
            assertTrue(output.contains("SETUP_RAN:acceptance:" + mode), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void checksModesAfterAllConfigurationProvidersResolve() throws Exception {
        fixture(true);
        append("""
            awsEmulator.useWith(tasks.named('test'), EmulatorMode.DEVELOPMENT, queue,
                providers.provider {
                    queue.configure { mode.convention(EmulatorMode.DEVELOPMENT) }
                    []
                })
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test").build().getOutput();
            assertTrue(output.contains("SETUP_RAN:default:DEVELOPMENT"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    @Test
    void checksPrerequisitesAddedByConfigurationProviders() throws Exception {
        fixture(true);
        append("""
            def late = tasks.register('late', Probe) { mode.set(EmulatorMode.DEVELOPMENT) }
            awsEmulator.useWith(tasks.named('test'), EmulatorMode.MANAGED, queue,
                providers.provider {
                    queue.configure { dependsOn(late) }
                    []
                })
            """);
        String output = runner(project, "test").buildAndFail().getOutput();
        assertTrue(output.contains("setup task :late uses DEVELOPMENT but consumer :test uses MANAGED"), output);
        assertFalse(output.contains("SETUP_RAN:"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsSharingOneSetupTaskBetweenDifferentConsumerModes(boolean reverseOrder) throws Exception {
        fixture(true);
        append("""
            awsEmulator.useWith(tasks.named('test'), EmulatorMode.MANAGED, queue)
            awsEmulator.useWith(tasks.register('developmentTest', Test), EmulatorMode.DEVELOPMENT, queue)
            """);
        String output = runner(project, reverseOrder ? "developmentTest" : "test",
            reverseOrder ? "test" : "developmentTest").buildAndFail().getOutput();
        assertTrue(output.contains("setup task :queue uses MANAGED but consumer :developmentTest uses DEVELOPMENT"), output);
        assertFalse(output.contains("SETUP_RAN:"), output);
    }

    @Test
    void kotlinExternalConsumersAlsoRejectMismatchedSetupBeforeReadingConnectionFiles() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"kotlin-external-modes\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            val setup = tasks.register<ListSqsQueuesTask>("prepareQueue") { mode.set(EmulatorMode.DEVELOPMENT) }
            awsEmulator {
                external {
                    endpoint.set("https://localhost:9297")
                    credentialsFile.set(layout.projectDirectory.file("missing-credentials"))
                    certificateFile.set(layout.projectDirectory.file("missing-certificate"))
                }
                useWith(tasks.named<Test>("test"), EmulatorMode.MANAGED, setup)
            }
            """);
        String output = runner(project, "test").buildAndFail().getOutput();
        assertTrue(output.contains("setup task :prepareQueue uses DEVELOPMENT but consumer :test uses MANAGED"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void sharedRuntimeConsumersValidateTheirLocalSetupUnderIsolation() throws Exception {
        fixture(true);
        Files.writeString(project.resolve("settings.gradle"), "include 'app'\n", StandardOpenOption.APPEND);
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("app/build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def setup = tasks.register('prepareQueue', ListSqsQueuesTask) { mode.set(EmulatorMode.DEVELOPMENT) }
            awsEmulator {
                useSharedRuntime(':')
                useWith(tasks.named('test'), EmulatorMode.MANAGED, setup)
            }
            """);
        String output = runner(project, ":app:test").buildAndFail().getOutput();
        assertTrue(output.contains("setup task :app:prepareQueue uses DEVELOPMENT but consumer :app:test uses MANAGED"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
        assertFalse(Files.exists(project.resolve("app/.aws-emulator")));
    }

    @ParameterizedTest
    @CsvSource({"MANAGED,DEVELOPMENT,false", "DEVELOPMENT,MANAGED,false",
        "MANAGED,DEVELOPMENT,true", "DEVELOPMENT,MANAGED,true"})
    void resourceSetPrerequisitesMustMatchLocalAndSharedConsumers(String consumerMode, String setupMode,
            boolean shared) throws Exception {
        fixture(true);
        append("""
            queue.configure { mode.set(EmulatorMode.%s) }
            def resources = awsEmulator.resourceSets.register('app') {
                provision('withPrerequisite', Probe) { dependsOn(queue) }
            }
            """.formatted(setupMode));
        String consumer = "test";
        if (shared) {
            Files.writeString(project.resolve("settings.gradle"), "include 'app'\n", StandardOpenOption.APPEND);
            Files.createDirectories(project.resolve("app"));
            Files.writeString(project.resolve("app/build.gradle"), """
                import io.github.gregwhitaker.awsemulator.model.EmulatorMode
                plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
                awsEmulator {
                    useSharedRuntime(':', 'app')
                    useWith(tasks.named('test'), EmulatorMode.%s)
                }
                """.formatted(consumerMode));
            consumer = ":app:test";
        } else {
            append("awsEmulator.useWith(tasks.named('test'), EmulatorMode." + consumerMode + ", resources)\n");
        }
        String output = runner(project, consumer).buildAndFail().getOutput();
        assertTrue(output.contains("setup task :queue uses " + setupMode + " but consumer :"), output);
        assertTrue(output.contains(" uses " + consumerMode + ". Set the setup task's mode"), output);
        assertFalse(output.contains("SETUP_RAN:"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(boolean isolated) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'setup-modes'\n");
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.DownloadS3ObjectTask as Probe
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            def queue = tasks.register('queue', Probe) {
                bucketName.set('fixture-bucket')
                objectKey.set('fixture.txt')
                outputFile.set(layout.buildDirectory.file('fixture.txt'))
                actions.clear()
                doLast { task ->
                    logger.lifecycle('SETUP_RAN:' + task.runtimeName.get() + ':' + task.mode.get())
                    def file = task.outputFile.get().asFile
                    file.parentFile.mkdirs()
                    file.text = 'fixture'
                }
            }
            def intermediate = tasks.register('intermediate') { inputs.files(queue.flatMap { it.outputFile }) }
            def wrapper = tasks.register('prepareWrapped') { dependsOn(intermediate) }
            awsEmulator {
                dockerExecutable.set('must-not-start-docker')
                runtimes { register('acceptance') }
            }
            """);
    }

    private void append(String text) throws Exception {
        Files.writeString(project.resolve("build.gradle"), text, StandardOpenOption.APPEND);
    }
}
