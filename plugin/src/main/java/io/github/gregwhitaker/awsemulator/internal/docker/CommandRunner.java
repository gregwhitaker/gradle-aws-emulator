package io.github.gregwhitaker.awsemulator.internal.docker;

import org.gradle.api.GradleException;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface CommandRunner {
    Result run(List<String> command, Duration timeout);

    record Result(int exitCode, String output) {
        public String requireSuccess(String operation) {
            if (exitCode != 0) {
                throw new GradleException(operation + " failed (exit " + exitCode + ").\n" + output);
            }
            return output.trim();
        }
    }
}
