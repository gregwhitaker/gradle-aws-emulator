package example;

import io.micronaut.context.ApplicationContext;

/** Runs a Micronaut-managed AWS client against the connection supplied by Gradle. */
public class Application {
    public static void main(String[] args) {
        try (var context = ApplicationContext.run()) {
            String message = context.getBean(Messages.class).roundTrip();
            System.out.println("Received from AWS Emulator: " + message);
            if (Boolean.getBoolean("example.fail")) throw new IllegalStateException("Intentional application failure");
        }
    }
}
