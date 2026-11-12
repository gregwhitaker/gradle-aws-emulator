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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies the runnable framework examples into isolated TestKit builds. */
public final class FrameworkExamples {
    private FrameworkExamples() {}

    public static void copyTo(Path project, String framework) throws IOException {
        Path source = Path.of(System.getProperty("framework.examples")).resolve(framework);
        Files.copy(source.resolve("build.gradle.kts"), project.resolve("build.gradle.kts"));
        // TestKit supplies the plugin classpath; preserve the framework's real plugin management.
        Files.writeString(project.resolve("settings.gradle.kts"), Files.readString(source.resolve("settings.gradle.kts"))
            .replace("    includeBuild(\"../..\")", ""));
        try (var files = Files.walk(source.resolve("src"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = project.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }
}
