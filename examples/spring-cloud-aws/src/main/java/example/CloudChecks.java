package example;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Checks Spring's imported configuration and auto-configured asynchronous client over verified TLS. */
@Component
public class CloudChecks {
    private final SqsAsyncClient sqs;
    private final SqsTemplate template;
    private final EventListener listener;
    private final String queue;
    private final String greeting;
    private final String token;

    public CloudChecks(SqsAsyncClient sqs, SqsTemplate template, EventListener listener,
            @Value("${example.queue}") String queue, @Value("${example.greeting}") String greeting,
            @Value("${example.token}") String token) {
        this.sqs = sqs;
        this.template = template;
        this.listener = listener;
        this.queue = queue;
        this.greeting = greeting;
        this.token = token;
    }

    public void verify() throws Exception {
        require("hello-from-parameter-store".equals(greeting), "Parameter Store import");
        require("local-example-token".equals(token), "Secrets Manager import");
        String endpoint = System.getProperty("aws.endpointUrl");
        require("https".equals(URI.create(endpoint).getScheme()), "HTTPS endpoint");
        require(sqs.serviceClientConfiguration().endpointOverride().orElseThrow().toString().equals(endpoint), "local async client");
        Files.writeString(Path.of(System.getProperty("example.connectionFile", "build/boot-connection.txt")),
            endpoint + "\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
        listener.await("fixture-event");
        String url = sqs.getQueueUrl(r -> r.queueName(queue)).get(30, TimeUnit.SECONDS).queueUrl();
        String asyncMessage = "async-" + UUID.randomUUID();
        sqs.sendMessage(r -> r.queueUrl(url).messageBody(asyncMessage)).get(30, TimeUnit.SECONDS);
        listener.await(asyncMessage);
        String templateMessage = "template-" + UUID.randomUUID();
        template.sendAsync(queue, templateMessage).get(30, TimeUnit.SECONDS);
        listener.await(templateMessage);
        System.out.println("SPRING_CLOUD_AWS_VERIFIED: imports, async client, template, listener and acknowledgements");
    }

    private static void require(boolean condition, String feature) {
        if (!condition) throw new AssertionError("Failed to verify " + feature);
    }
}
