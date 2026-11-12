package example;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
class MessagesTest {
    @Inject Messages messages;

    @Test void frameworkManagedClientUsesTheProvisionedQueueOverHttps() {
        assertTrue(System.getenv("AWS_ENDPOINT_URL").startsWith("https://127.0.0.1:"));
        assertTrue(messages.roundTrip().startsWith("Hello from Micronaut: "));
        assertFalse(Boolean.getBoolean("example.fail"), "Intentional test failure");
    }
}
