package io.github.gregwhitaker.awsemulator.internal.docker;

import org.gradle.api.GradleException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes argument lists directly; drains output while waiting and bounds retained diagnostics. */
public final class ProcessCommandRunner implements CommandRunner {
    @Override
    public Result run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            Process child = process;
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStreamReader stream = new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (output) {
                            output.append(buffer, 0, count);
                            if (output.length() > 65536) output.delete(0, output.length() - 65536);
                        }
                    }
                } catch (IOException ignored) {
                    // The stream can close while a timed-out process is being destroyed.
                }
            }, "aws-emulator-command-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new GradleException(command.get(0) + " timed out after " + timeout.toSeconds() + " seconds.");
            }
            reader.join(5000);
            synchronized (output) { return new Result(process.exitValue(), output.toString()); }
        } catch (IOException e) {
            throw new GradleException("Cannot execute '" + command.get(0)
                + "'. Install Docker and ensure its CLI is on PATH, or set awsEmulator.dockerExecutable.", e);
        } catch (InterruptedException e) {
            if (process != null) terminate(process);
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while executing " + command.get(0), e);
        }
    }

    private static void terminate(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // Sandboxed hosts may prohibit process enumeration. Always terminate our direct child.
        } finally {
            process.destroyForcibly();
        }
    }
}
