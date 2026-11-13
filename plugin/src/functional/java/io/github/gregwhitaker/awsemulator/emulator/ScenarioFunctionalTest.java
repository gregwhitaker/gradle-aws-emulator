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

class ScenarioFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void scenariosAreLazyAndSeedInDependencyOrderInBothDsls(boolean kotlin, boolean isolated) throws Exception {
        fixture(kotlin);
        if (isolated) Files.writeString(project.resolve("gradle.properties"), "org.gradle.unsafe.isolated-projects=true\n");
        String tasks = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(tasks.contains("seedAwsEmulatorDemo"), tasks);
        assertTrue(tasks.contains("resetAwsEmulatorEmpty"), tasks);
        assertFalse(tasks.contains("PROBE:"), tasks);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "seedAwsEmulator", "--parallel", "--max-workers=4").build().getOutput();
            ordered(output, "PROBE:START", "PROBE:createData", "PROBE:seedData", "PROBE:seedMore");
            assertFalse(output.contains("PROBE:RESET"), output);
            assertFalse(output.contains("PROBE:unused"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    @Test void resetPrecedesProvisioningAndSeedingIncludingOnCacheReuse() throws Exception {
        fixture(false);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "resetAwsEmulator", "--parallel", "--max-workers=4").build().getOutput();
            ordered(output, "PROBE:RESET", "PROBE:START", "PROBE:createData", "PROBE:seedData", "PROBE:seedMore");
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
    }

    @Test void propertySelectionAndNamedCommandsDoNotExecuteUnselectedFixtures() throws Exception {
        fixture(false);
        String empty = runner(project, "seedAwsEmulator", "-PawsEmulator.scenario=empty").build().getOutput();
        assertTrue(empty.contains("PROBE:createData"), empty);
        assertFalse(empty.contains("PROBE:seed"), empty);
        assertFalse(empty.contains("PROBE:RESET"), empty);
        String named = runner(project, "resetAwsEmulatorEmpty").build().getOutput();
        ordered(named, "PROBE:RESET", "PROBE:START", "PROBE:createData");
        assertFalse(named.contains("PROBE:seed"), named);
    }

    @Test void validatesSelectionBeforeExecutingAnyLifecycleTask() throws Exception {
        fixture(false);
        String unknown = runner(project, "resetAwsEmulator", "-PawsEmulator.scenario=missing").buildAndFail().getOutput();
        assertTrue(unknown.contains("Unknown AWS Emulator scenario 'missing'"), unknown);
        assertFalse(unknown.contains("PROBE:RESET"), unknown);
        String multiple = runner(project, "seedAwsEmulatorDemo", "seedAwsEmulatorEmpty").buildAndFail().getOutput();
        assertTrue(multiple.contains("Select only one AWS Emulator scenario"), multiple);
        assertFalse(multiple.contains("PROBE:START"), multiple);
    }

    @Test void provisioningFailurePreventsSeedsEvenWithContinue() throws Exception {
        fixture(false);
        Files.writeString(project.resolve("build.gradle"), """
            tasks.named('createData') { doLast { throw new GradleException('intentional provisioning failure') } }
            """, java.nio.file.StandardOpenOption.APPEND);
        String output = runner(project, "seedAwsEmulator", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("intentional provisioning failure"), output);
        assertFalse(output.contains("PROBE:seedData"), output);
        assertFalse(output.contains("PROBE:seedMore"), output);
    }

    @Test void failedRestartBlocksAllDevelopmentSeedingEvenWithContinue() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'failed-reset'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            def bucket = tasks.register('createData', CreateS3BucketTask) { bucketName.set('fixture-bucket') }
            awsEmulator {
                dockerExecutable.set('this-command-does-not-exist')
                scenarios { register('default') { provision(bucket) } }
            }
            """);
        String output = runner(project, "resetAwsEmulator", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("Cannot seed the development emulator after its restart failed"), output);
        assertFalse(output.contains("S3 bucket ready"), output);
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
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'scenario-fixture'\n");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            val create = tasks.register<ListS3BucketsTask>("createData")
            val seedTask = tasks.register<ListS3BucketsTask>("seedData")
            val more = tasks.register<ListS3BucketsTask>("seedMore")
            tasks.register<ListS3BucketsTask>("unused")
            tasks.withType<ListS3BucketsTask>().configureEach {
                actions.clear(); doLast { logger.lifecycle("PROBE:$name") }
            }
            awsEmulator {
                dockerExecutable.set("this-command-does-not-exist")
                defaultScenario.set("demo")
                scenarios {
                    register("demo") { provision(create); seed(seedTask, more) }
                    register("empty") { provision(create) }
                }
            }
            tasks.named("startAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:START") } }
            tasks.named("restartAwsEmulator") { actions.clear(); doLast { logger.lifecycle("PROBE:RESET") } }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.s3.ListS3BucketsTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            def create = tasks.register('createData', ListS3BucketsTask)
            def seedTask = tasks.register('seedData', ListS3BucketsTask)
            def more = tasks.register('seedMore', ListS3BucketsTask)
            tasks.register('unused', ListS3BucketsTask)
            tasks.withType(ListS3BucketsTask).configureEach {
                actions.clear(); doLast { logger.lifecycle("PROBE:${name}") }
            }
            awsEmulator {
                dockerExecutable.set('this-command-does-not-exist')
                defaultScenario.set('demo')
                scenarios {
                    register('demo') { provision(create); seed(seedTask, more) }
                    register('empty') { provision(create) }
                }
            }
            tasks.named('startAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:START') } }
            tasks.named('restartAwsEmulator') { actions.clear(); doLast { logger.lifecycle('PROBE:RESET') } }
            """);
    }
}
