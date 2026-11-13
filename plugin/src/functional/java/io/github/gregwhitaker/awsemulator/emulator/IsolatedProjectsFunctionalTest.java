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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.isolatedRunner;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedProjectsFunctionalTest {
    @TempDir Path project;

    @Test
    void sharesNamedOwnerSettingsAndResourcesWithParallelConfigurationAndCacheReuse() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'isolated-runtime-fixture'\ninclude 'api', 'worker', 'infra'\n");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.github.gregwhitaker.aws-emulator' apply false }\n");
        for (String child : List.of("api", "worker", "infra")) Files.createDirectories(project.resolve(child));
        Files.writeString(project.resolve("infra/build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('acceptance') { port.set(12345) } }
                resourceSets.register('app') {
                    provision('queue', ListSqsQueuesTask) {
                        actions.clear()
                        doLast { task -> logger.lifecycle('SETUP:' + task.runtimeName.get() + ':' + task.mode.get()) }
                    }
                }
                resourceSets.register('unused') { throw new GradleException('Unused resource set configured') }
            }
            """);
        for (String child : List.of("api", "worker")) {
            Files.writeString(project.resolve(child + "/build.gradle"), """
                import io.github.gregwhitaker.awsemulator.emulator.StatusAwsEmulatorTask
                plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
                awsEmulator {
                    useSharedRuntime(':infra', 'app')
                    useWith(tasks.named('test'), 'acceptance')
                }
                tasks.withType(StatusAwsEmulatorTask).configureEach {
                    actions.clear()
                    doLast { task -> logger.lifecycle('OWNER:' + task.path + ':' + task.runtime.get().parameters.port.get()) }
                }
                """);
        }
        String[] tasks = {":api:test", ":worker:test", ":api:statusAwsEmulatorAcceptance", ":worker:statusAwsEmulatorAcceptance",
            "--parallel", "--max-workers=4"};
        for (int run = 0; run < 2; run++) {
            String output = isolatedRunner(project, tasks).build().getOutput();
            assertEquals(1, output.lines().filter(line -> line.equals("SETUP:acceptance:MANAGED")).count(), output);
            assertTrue(output.contains("OWNER::api:statusAwsEmulatorAcceptance:12345"), output);
            assertTrue(output.contains("OWNER::worker:statusAwsEmulatorAcceptance:12345"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        Path owner = project.resolve("infra/build.gradle");
        Files.writeString(owner, Files.readString(owner).replace("12345", "23456"));
        String changed = isolatedRunner(project, tasks).build().getOutput();
        assertTrue(changed.contains("OWNER::api:statusAwsEmulatorAcceptance:23456"), changed);
        assertTrue(changed.contains("OWNER::worker:statusAwsEmulatorAcceptance:23456"), changed);
        assertTrue(isolatedRunner(project, tasks).build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve("infra/.aws-emulator")));
    }

    @Test
    void runtimeOnlySharingSurvivesConsumerChangesWithoutOwnerTasks() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "include 'app', 'infra'\n");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.github.gregwhitaker.aws-emulator' apply false }\n");
        Files.createDirectories(project.resolve("infra"));
        Files.writeString(project.resolve("infra/build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('acceptance') { port.set(12345) } }
            }
            """);
        Files.createDirectories(project.resolve("app"));
        Path consumer = project.resolve("app/build.gradle");
        Files.writeString(consumer, """
            import io.github.gregwhitaker.awsemulator.emulator.StatusAwsEmulatorTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator.useSharedRuntime(':infra')
            tasks.withType(StatusAwsEmulatorTask).configureEach {
                actions.clear()
                doLast { task -> logger.lifecycle('OWNER_PORT:' + task.runtime.get().parameters.port.get()) }
            }
            """);
        for (int run = 0; run < 3; run++) {
            if (run == 1) Files.writeString(consumer, "\n// Consumer configuration changed\n", java.nio.file.StandardOpenOption.APPEND);
            var result = isolatedRunner(project, ":app:statusAwsEmulatorAcceptance").build();
            assertTrue(result.getOutput().contains("OWNER_PORT:12345"), result.getOutput());
            assertTrue(result.getTasks().stream().noneMatch(task -> task.getPath().startsWith(":infra:")));
            if (run == 2) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
        }
        assertFalse(Files.exists(project.resolve("infra/.aws-emulator")));
    }

    @Test
    void sharedExternalScenarioUsesOwnerMetadataWithoutStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'isolated-external-fixture'\ninclude 'app'\n");
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('must-not-invoke-docker')
                runtimes { register('remote') { external { } } }
            }
            """);
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("app/build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                useSharedRuntime(':')
                def applicationResources = resourceSets.register('app') {
                    provision('queue', ListSqsQueuesTask) {
                        actions.clear()
                        doLast { task -> logger.lifecycle('EXTERNAL:' + task.runtime.get().parameters.externalEnabled.get()) }
                    }
                }
                scenarios { register('remoteData') { runtimeName.set('remote'); resources(applicationResources) } }
            }
            """);
        for (int run = 0; run < 2; run++) {
            String output = isolatedRunner(project, ":app:seedAwsEmulatorRemoteData").build().getOutput();
            assertTrue(output.contains("EXTERNAL:true"), output);
            assertFalse(output.contains("> Task :app:startAwsEmulator"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
