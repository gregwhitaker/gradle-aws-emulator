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

package io.github.gregwhitaker.awsemulator.aws;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class DefaultModeFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void customTasksInheritLazyDefaultWhileExplicitModesAndConsumersKeepTheirSelection(boolean kotlin) throws Exception {
        fixture(kotlin);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "early", "late", "managed", "listS3Buckets", "listSqsQueues", "test").build().getOutput();
            for (String task : new String[] {"early", "late", "listS3Buckets", "listSqsQueues"}) {
                assertTrue(output.contains("MODE:" + task + ":DEVELOPMENT"), output);
            }
            assertTrue(output.contains("MODE:managed:MANAGED"), output);
            assertTrue(output.contains("NO-SOURCE"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commandLineOverridesTheExtensionDefaultOnConfigurationCacheReuse(boolean kotlin) throws Exception {
        fixture(kotlin);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "early", "--mode=MANAGED", "late").build().getOutput();
            assertTrue(output.contains("MODE:early:MANAGED"), output);
            assertTrue(output.contains("MODE:late:DEVELOPMENT"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(boolean kotlin) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'default-mode'\n");
        String imports = """
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            """;
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), imports + (kotlin ? """
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("docker-must-not-run") }
            tasks.withType<AbstractAwsResourceTask>().configureEach {
                actions.clear()
                doLast {
                    val selected = javaClass.getMethod("getMode").invoke(this) as Provider<*>
                    logger.lifecycle("MODE:${name}:${selected.get()}")
                }
            }
            val early = tasks.register<ListS3BucketsTask>("early")
            check(awsEmulator.defaultMode.get() == EmulatorMode.MANAGED)
            check(early.get().mode.get() == EmulatorMode.MANAGED)
            check(tasks.named<ListS3BucketsTask>("listS3Buckets").get().mode.get() == EmulatorMode.DEVELOPMENT)
            val selected = objects.property(EmulatorMode::class.java).convention(EmulatorMode.MANAGED)
            awsEmulator {
                defaultMode.set(selected)
                runtimes { register("other") }
                useWith(tasks.named<Test>("test"))
            }
            selected.set(EmulatorMode.DEVELOPMENT)
            tasks.register<ListSqsQueuesTask>("late") { runtimeName.set("other") }
            tasks.register<ListS3BucketsTask>("managed") { mode.set(EmulatorMode.MANAGED) }
            tasks.register<ListS3BucketsTask>("unused") { error("Unused resource task was realized") }
            val consumerMode = tasks.named<Test>("test").get().extensions.extraProperties.get("awsEmulatorMode") as Provider<*>
            check(consumerMode.get() == EmulatorMode.MANAGED)
            """ : """
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('docker-must-not-run') }
            tasks.withType(AbstractAwsResourceTask).configureEach {
                actions.clear()
                doLast { task -> task.logger.lifecycle("MODE:${task.name}:${task.mode.get()}") }
            }
            def early = tasks.register('early', ListS3BucketsTask)
            assert awsEmulator.defaultMode.get() == EmulatorMode.MANAGED
            assert early.get().mode.get() == EmulatorMode.MANAGED
            assert tasks.named('listS3Buckets').get().mode.get() == EmulatorMode.DEVELOPMENT
            def selected = objects.property(EmulatorMode).convention(EmulatorMode.MANAGED)
            awsEmulator {
                defaultMode.set(selected)
                runtimes { register('other') }
                useWith(tasks.named('test'))
            }
            selected.set(EmulatorMode.DEVELOPMENT)
            tasks.register('late', ListSqsQueuesTask) { runtimeName.set('other') }
            tasks.register('managed', ListS3BucketsTask) { mode.set(EmulatorMode.MANAGED) }
            tasks.register('unused', ListS3BucketsTask) { throw new GradleException('Unused resource task was realized') }
            assert tasks.named('test').get().awsEmulatorMode.get() == EmulatorMode.MANAGED
            """));
    }
}
