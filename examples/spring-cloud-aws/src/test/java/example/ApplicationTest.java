package example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@DirtiesContext
class ApplicationTest {
    @Autowired CloudChecks checks;

    @Test void importsConfigurationAndConsumesMessagesUsingSpringCloudAws() throws Exception {
        checks.verify();
        assertFalse(Boolean.getBoolean("example.fail"), "Intentional test failure");
    }
}
