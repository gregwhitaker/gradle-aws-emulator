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
