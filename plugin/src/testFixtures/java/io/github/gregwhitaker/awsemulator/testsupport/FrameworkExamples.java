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
