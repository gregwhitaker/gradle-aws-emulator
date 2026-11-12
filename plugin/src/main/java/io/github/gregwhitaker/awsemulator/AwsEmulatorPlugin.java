package io.github.gregwhitaker.awsemulator;

import io.github.gregwhitaker.awsemulator.aws.AwsModule;
import io.github.gregwhitaker.awsemulator.emulator.EmulatorModule;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.util.GradleVersion;

/** Entry point for io.github.gregwhitaker.aws-emulator. */
public class AwsEmulatorPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        if (GradleVersion.current().compareTo(GradleVersion.version("8.14")) < 0) {
            throw new GradleException("AWS Emulator requires Gradle 8.14 or newer and Java 17 or newer.");
        }
        EmulatorModule.load(project);
        AwsModule.load(project);
    }
}
