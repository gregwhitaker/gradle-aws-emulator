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

    /** Starts a streaming command with separate binary stdout and diagnostic stderr. */
    default Process start(List<String> command) throws IOException {
        throw new UnsupportedOperationException("This command runner does not support streaming processes.");
    }

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
