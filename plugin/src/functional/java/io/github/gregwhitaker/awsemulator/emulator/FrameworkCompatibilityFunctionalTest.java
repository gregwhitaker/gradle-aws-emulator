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

import io.github.gregwhitaker.awsemulator.testsupport.FrameworkExamples;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkCompatibilityFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @CsvSource({"micronaut, 4.9.4", "micronaut, 4.10.24", "ktor, 3.5.2", "ktor, 3.6.1"})
    void compilesActualFrameworkApplicationsAndTestsWithoutDockerAndReusesConfigurationCache(String framework, String version)
            throws Exception {
        fixture(framework);
        String versionProperty = "-P" + framework + "Version=" + version;
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "classes", "testClasses", versionProperty).build().getOutput();
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertFalse(Files.exists(project.resolve(".aws-emulator")), "Compilation must not start the emulator");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"micronaut", "ktor"})
    void selectsDevelopmentForRunAndManagedForTestsAndOrdersProvisioningBeforeConsumers(String framework) throws Exception {
        fixture(framework);
        Files.writeString(project.resolve("build.gradle.kts"), """

            check(tasks.named<JavaExec>("run").get().extensions.extraProperties
                .get("awsEmulatorMode").let { it as Provider<*> }.get() == EmulatorMode.DEVELOPMENT)
            check(tasks.named<Test>("test").get().extensions.extraProperties
                .get("awsEmulatorMode").let { it as Provider<*> }.get() == EmulatorMode.MANAGED)
            check(tasks.named<CreateSqsQueueTask>("createApplicationQueue").get().mode.get() == EmulatorMode.DEVELOPMENT)
            check(tasks.named<CreateSqsQueueTask>("createTestQueue").get().mode.get() == EmulatorMode.MANAGED)
            val ordinary = tasks.register<JavaExec>("ordinaryRun").get()
            check(!ordinary.extensions.extraProperties.has("awsEmulatorMode"))
            """, StandardOpenOption.APPEND);
        String help = runner(project, "help", "-Pdevelopment=true").build().getOutput();
        assertFalse(help.contains("AWS Emulator for"), help);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test", "run", "--debug-jvm", "--dry-run", "-Pdevelopment=true").build().getOutput();
            assertBefore(output, ":createTestQueue SKIPPED", ":test SKIPPED");
            assertBefore(output, ":createApplicationQueue SKIPPED", ":run SKIPPED");
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(String framework) throws Exception {
        FrameworkExamples.copyTo(project, framework);
        Files.writeString(project.resolve("build.gradle.kts"), """

            awsEmulator { dockerExecutable.set("no-docker-during-framework-configuration") }
            """, StandardOpenOption.APPEND);
    }

    private void assertBefore(String output, String setup, String consumer) {
        assertTrue(output.contains(setup), output);
        assertTrue(output.indexOf(consumer) > output.indexOf(setup), output);
    }
}
