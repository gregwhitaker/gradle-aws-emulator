package io.github.gregwhitaker.awsemulator.internal.docker;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProcessCommandRunnerTest {
    @TempDir Path directory;

    @Test
    void archivesCompleteOutputWithoutTheDiagnosticBufferLimit() throws Exception {
        Path output = directory.resolve("container.log");
        var result = new ProcessCommandRunner().runToFile(command("flood"), Duration.ofSeconds(10), output);
        assertEquals(7, result.exitCode());
        assertEquals("A".repeat(131072) + "complete", Files.readString(output));
        assertThrows(GradleException.class, () -> new ProcessCommandRunner().runToFile(command("pause"), Duration.ofMillis(300), output));
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

    private static List<String> command(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, Probe.class.getName(), mode);
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
