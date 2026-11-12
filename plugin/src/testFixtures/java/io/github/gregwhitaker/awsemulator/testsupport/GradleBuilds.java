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

package io.github.gregwhitaker.awsemulator.testsupport;

import org.gradle.testkit.runner.GradleRunner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

public final class GradleBuilds {
    private GradleBuilds() {}

    public static GradleRunner runner(Path project, String... tasks) {
        var args = new ArrayList<>(Arrays.asList(tasks));
        args.addAll(Arrays.asList("--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace", "--console=plain"));
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
        configureCoverage(runner, project);
        String installation = System.getProperty("test.gradle.installation", "");
        String version = System.getProperty("test.gradle.version", "");
        if (!installation.isBlank()) runner.withGradleInstallation(new File(installation));
        else if (!version.isBlank()) runner.withGradleVersion(version);
        return runner;
    }

    private static void configureCoverage(GradleRunner runner, Path project) {
        String properties = System.getProperty("test.jacoco.properties", "");
        if (properties.isBlank()) return;

        // Replace the TestKit extension's default destination with this suite's file.
        var classpath = new ArrayList<File>();
        classpath.add(new File(properties));
        for (File entry : runner.getPluginClasspath()) {
            if (!new File(entry, "jacoco-agent.properties").isFile()) classpath.add(entry);
        }
        runner.withPluginClasspath(classpath);

        // The injected plugin classpath is available after a project applies the
        // plugin under test. Register a cache-compatible coverage dump at that point.
        Path initScript = project.resolve(".gradle/jacoco-testkit.init.gradle");
        String script = """
            gradle.afterProject { project, state ->
                if (project.plugins.hasPlugin('io.github.gregwhitaker.aws-emulator')) {
                    project.pluginManager.apply('jacoco-testkit-coverage')
                }
            }
            """;
        try {
            Files.createDirectories(initScript.getParent());
            if (!Files.exists(initScript) || !Files.readString(initScript).equals(script)) {
                Files.writeString(initScript, script);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot enable TestKit coverage in " + project, e);
        }
        var args = new ArrayList<>(runner.getArguments());
        // JaCoCo reads system properties while Gradle's Kotlin support may initialize
        // this one lazily. Keep it stable on Gradle < 9.7 (gradle/gradle#30145).
        args.add("-Didea.io.use.nio2=true");
        args.addAll(Arrays.asList("--init-script", initScript.toString()));
        runner.withArguments(args);
    }
}
