package io.github.gregwhitaker.awsemulator.internal.docker;

import org.gradle.api.GradleException;
import java.time.Duration;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@FunctionalInterface
public interface CommandRunner {
    Result run(List<String> command, Duration timeout);

    /** Streams output to a file; the default also supports in-memory command fixtures. */
    default Result runToFile(List<String> command, Duration timeout, Path output) throws IOException {
        Result result = run(command, timeout);
        Files.writeString(output, result.output());
        return result;
    }

    record Result(int exitCode, String output) {
        public String requireSuccess(String operation) {
            if (exitCode != 0) {
                throw new GradleException(operation + " failed (exit " + exitCode + ").\n" + output);
            }
            return output.trim();
        }
    }
}
