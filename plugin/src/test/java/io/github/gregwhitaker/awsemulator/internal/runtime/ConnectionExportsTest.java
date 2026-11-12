package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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
}
