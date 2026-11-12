package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorConnectionTest {
    @TempDir Path directory;

    @Test
    void replacesCredentialAndEndpointOverridesOnlyInTheChildEnvironment() throws Exception {
        var connection = new EmulatorConnection(9297, "eu-west-1", directory);
        var inherited = Map.of("AWS_ACCESS_KEY_ID", "real-key", "AWS_SESSION_TOKEN", "real-token",
            "AWS_PROFILE", "production", "AWS_ENDPOINT_URL_STS", "https://aws.example",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI", "https://credentials.example", "PATH", "/bin");
        var env = connection.environment(inherited);
        assertEquals("real-key", inherited.get("AWS_ACCESS_KEY_ID"));
        assertFalse(env.containsKey("AWS_ACCESS_KEY_ID"));
        assertFalse(env.containsKey("AWS_SESSION_TOKEN"));
        assertFalse(env.containsKey("AWS_PROFILE"));
        assertFalse(env.containsKey("AWS_ENDPOINT_URL_STS"));
        assertFalse(env.containsKey("AWS_CONTAINER_CREDENTIALS_FULL_URI"));
        assertEquals("/bin", env.get("PATH"));
        assertEquals("https://127.0.0.1:9297", env.get("AWS_ENDPOINT_URL"));
        assertEquals("eu-west-1", env.get("AWS_REGION"));
        connection.writeHostConfig();
        String config = Files.readString(directory.resolve("host-config"));
        assertTrue(config.contains("region = eu-west-1"));
        assertTrue(config.contains(directory.resolve("ca.pem").toString()));
    }

    @Test
    void overridesJvmCredentialsAndTrustWithoutChangingTheGradleJvm() {
        var connection = new EmulatorConnection(9300, "us-east-1", directory);
        String parentTrustStore = System.getProperty("javax.net.ssl.trustStore");
        var props = connection.systemProperties(Map.of("aws.accessKeyId", "real-key", "aws.secretAccessKey", "secret",
            "aws.endpointUrl.s3", "https://aws.example", "javax.net.ssl.trustStorePassword", "old-password", "custom", "kept"));
        assertFalse(props.containsKey("aws.accessKeyId"));
        assertFalse(props.containsKey("aws.secretAccessKey"));
        assertFalse(props.containsKey("aws.endpointUrl.s3"));
        assertFalse(props.containsKey("javax.net.ssl.trustStorePassword"));
        assertEquals("kept", props.get("custom"));
        assertEquals(directory.resolve("truststore.p12").toString(), props.get("javax.net.ssl.trustStore"));
        assertEquals(parentTrustStore, System.getProperty("javax.net.ssl.trustStore"));
    }
}
