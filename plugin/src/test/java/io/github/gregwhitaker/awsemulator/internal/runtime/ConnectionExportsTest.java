package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConnectionExportsTest {
    @TempDir Path directory;

    @Test
    void quotesShellMetacharactersAndPowerShellSingleQuotesWithoutEmbeddingCredentials() throws Exception {
        var connection = new EmulatorConnection(9234, "us-east-1", directory.resolve("a ' $name `command` folder"));
        ConnectionExports.write(connection, directory);
        String powershell = Files.readString(directory.resolve("connection.ps1"));
        assertTrue(powershell.contains("a '' $name `command` folder"));
        assertTrue(powershell.contains("$env:AWS_ENDPOINT_URL = 'https://127.0.0.1:9234'"));
        assertTrue(powershell.contains("Remove-Item -LiteralPath"));
        assertFalse(powershell.contains("$env:AWS_SECRET_ACCESS_KEY ="));
        String shell = Files.readString(directory.resolve("connection.sh"));
        assertTrue(shell.contains("a '\"'\"' $name `command` folder"));
        assertFalse(shell.contains("export AWS_SECRET_ACCESS_KEY="));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sourcedShellScriptRemovesOverridesPresentAtSourceTimeAndPreservesLiteralPaths() throws Exception {
        var connection = new EmulatorConnection(9234, "us-east-1", directory.resolve("a ' $name `command` folder"));
        ConnectionExports.write(connection, directory);
        Path output = directory.resolve("environment");
        var builder = new ProcessBuilder("sh", "-c", ". \"$1\"; env", "sh", directory.resolve("connection.sh").toString());
        builder.environment().put("AWS_ACCESS_KEY_ID", "production-key");
        builder.environment().put("AWS_ENDPOINT_URL_CUSTOM_SERVICE", "https://production.example");
        builder.environment().put("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "production-token");
        builder.environment().put("KEEP_ME", "unchanged");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        String environment = Files.readString(output);
        assertFalse(environment.contains("production"), environment);
        assertTrue(environment.contains("AWS_SHARED_CREDENTIALS_FILE=" + connection.credentials()), environment);
        assertTrue(environment.contains("KEEP_ME=unchanged"), environment);
        assertTrue(environment.contains("NODE_EXTRA_CA_CERTS=" + connection.certificate()), environment);
    }

    @Test
    void sourcedPowerShellScriptRemovesOverridesAndPreservesLiteralPathsWhenPowerShellIsInstalled() throws Exception {
        var connection = new EmulatorConnection(9234, "us-east-1", directory.resolve("a ' $name `command` folder"));
        ConnectionExports.write(connection, directory);
        Path probe = directory.resolve("probe.ps1");
        Files.writeString(probe, """
            param([string] $ConnectionScript, [string] $ResultFile)
            $ErrorActionPreference = 'Stop'
            . $ConnectionScript
            if (Test-Path Env:AWS_ACCESS_KEY_ID) { throw 'Credential override remains' }
            if (Test-Path Env:AWS_ENDPOINT_URL_CUSTOM_SERVICE) { throw 'Endpoint override remains' }
            [System.IO.File]::WriteAllText($ResultFile, $env:AWS_SHARED_CREDENTIALS_FILE)
            """);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "powershell" : "pwsh";
        Path output = directory.resolve("powershell-credentials-path");
        Path diagnostics = directory.resolve("powershell-output");
        ProcessBuilder builder = new ProcessBuilder(executable, "-NoProfile", "-NonInteractive", "-File", probe.toString(),
            directory.resolve("connection.ps1").toString(), output.toString());
        builder.environment().put("AWS_ACCESS_KEY_ID", "production");
        builder.environment().put("AWS_ENDPOINT_URL_CUSTOM_SERVICE", "https://production.invalid");
        Process process;
        try { process = builder.redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start(); }
        catch (java.io.IOException e) { assumeTrue(false, "PowerShell is not installed: " + e.getMessage()); return; }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished);
        assertEquals(0, process.exitValue(), Files.readString(diagnostics));
        assertEquals(connection.credentials().toString(), Files.readString(output));
    }
}
