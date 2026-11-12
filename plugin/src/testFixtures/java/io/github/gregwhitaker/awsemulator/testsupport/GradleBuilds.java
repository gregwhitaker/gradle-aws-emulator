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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

public final class GradleBuilds {
    private GradleBuilds() {}

    public static GradleRunner runner(Path project, String... tasks) {
        var args = new ArrayList<>(Arrays.asList(tasks));
        args.addAll(Arrays.asList("--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace", "--console=plain"));
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
        String installation = System.getProperty("test.gradle.installation", "");
        String version = System.getProperty("test.gradle.version", "");
        if (!installation.isBlank()) runner.withGradleInstallation(new File(installation));
        else if (!version.isBlank()) runner.withGradleVersion(version);
        return runner;
    }
}
