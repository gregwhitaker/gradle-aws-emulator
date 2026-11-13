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

    public static GradleRunner isolatedRunner(Path project, String... tasks) {
        var args = new ArrayList<>(Arrays.asList(tasks));
        args.add("-Dorg.gradle.unsafe.isolated-projects=true");
        return runner(project, args.toArray(String[]::new));
    }

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
            gradle.lifecycle.afterProject { project ->
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
        // JaCoCo reads all system properties, including Kotlin's lazy compiler settings.
        // Seed the values from IdeaStandaloneExecutionSetup.doSetup before configuration
        // so a fresh daemon can reuse its first configuration cache entry.
        // See gradle/gradle#30145 for the idea.io.use.nio2 workaround.
        args.addAll(Arrays.asList(
            "-Didea.io.use.nio2=true",
            "-Dproject.structure.add.tools.jar.to.new.jdk=false",
            "-Dpsi.track.invalidation=true",
            "-Dpsi.incremental.reparse.depth.limit=1000",
            "-Dpsi.sleep.in.validity.check=false",
            "-Dide.hide.excluded.files=false",
            "-Dast.loading.filter=false",
            "-Didea.ignore.disabled.plugins=true",
            "-Dplatform.random.idempotence.check.rate=1000",
            "-Didea.config.path=some/non/existent/path",
            "-Didea.plugins.compatible.build=999.SNAPSHOT",
            "-Dide.enable.implicit.blocking.context=false"
        ));
        args.addAll(Arrays.asList("--init-script", initScript.toString()));
        runner.withArguments(args);
    }
}
