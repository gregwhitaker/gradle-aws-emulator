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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ProcessCommandRunnerTest {
    @TempDir Path directory;

    @Test
    void archivesCompleteOutputWithoutTheDiagnosticBufferLimit() throws Exception {
        Path output = directory.resolve("container.log");
        var result = new ProcessCommandRunner().runToFile(command("flood"), Duration.ofSeconds(10), output);
        assertEquals(7, result.exitCode());
        // The JVM can prepend launcher diagnostics (for example, when JAVA_TOOL_OPTIONS is set).
        assertTrue(Files.readString(output).endsWith("A".repeat(131072) + "complete"),
            "The full child output must be archived without truncation");
        assertThrows(GradleException.class, () -> new ProcessCommandRunner().runToFile(command("pause"), Duration.ofMillis(300), output));
        Files.delete(output);
        assertFalse(Files.exists(output), "A timed-out child must release the log before runToFile returns");
    }

    @Test
    void drainsLargeOutputsWithoutDeadlockingAndKeepsExitStatus() throws Exception {
        var result = new ProcessCommandRunner().run(command("flood"), Duration.ofSeconds(10));
        assertEquals(7, result.exitCode());
        assertTrue(result.output().endsWith("complete"));
        assertTrue(result.output().length() <= 65536);
        assertThrows(GradleException.class, () -> result.requireSuccess("fixture"));
    }

    @Test
    void terminatesCommandsThatExceedTheirDeadline() throws Exception {
        List<String> command = command("pause");
        long start = System.nanoTime();
        var failure = assertThrows(GradleException.class,
            () -> new ProcessCommandRunner().run(command, Duration.ofMillis(300)));
        assertTrue(failure.getMessage().contains("timed out"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
    }

    @Test
    void waitsForNativeCleanupEvenWhenTheExitCodeIsAlreadyVisible() {
        CleanupProcess process = new CleanupProcess();

        ProcessCommandRunner.terminate(process);

        assertTrue(process.nativeCleanupComplete, "An exit code does not prove native file handles have been released");
    }

    @Test
    void interruptedCleanupStillWaitsAndRestoresTheInterruptFlag() {
        CleanupProcess process = new CleanupProcess();

        Thread.currentThread().interrupt();
        try {
            ProcessCommandRunner.terminate(process);
            assertTrue(process.nativeCleanupComplete, "Interrupted callers must still wait for native cleanup");
            assertTrue(Thread.currentThread().isInterrupted(), "Cleanup must preserve the caller's interrupt flag");
        } finally {
            Thread.interrupted();
        }
    }

    private static List<String> command(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, Probe.class.getName(), mode);
    }

    /** Models Windows exposing the exit code before the process handle is signaled. */
    private static final class CleanupProcess extends Process {
        private volatile boolean nativeCleanupComplete;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int exitValue() { return 1; }
        @Override public void destroy() { }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public int waitFor() { nativeCleanupComplete = true; return 1; }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("pause")) Thread.sleep(30000);
            else {
                System.out.print("A".repeat(131072));
                System.err.print("complete");
                System.exit(7);
            }
        }
    }
}
