package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Arrays;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        try (var context = SpringApplication.run(Application.class, args)) {
            context.getBean(CloudChecks.class).verify();
            if (Arrays.asList(args).contains("fail")) throw new IllegalStateException("Intentional application failure");
        }
    }
}
