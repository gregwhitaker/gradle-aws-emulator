package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SesIntegrationTest {
    @Test void sendsWithTheApplicationSdkAndObservesLocalEvents() throws Exception {
        try (var ses = SesV2Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder())
                 .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))).build();
             var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder())
                 .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))).build()) {
            assertTrue(ses.getEmailIdentity(r -> r.emailIdentity("sender@example.test")).verifiedForSendingStatus());
            var template = ses.getEmailTemplate(r -> r.templateName("example-welcome")).templateContent();
            assertEquals("Welcome {{name}}", template.subject());
            assertTrue(template.html().contains("{{name}}"));
            var mapper = new ObjectMapper();
            JsonNode fixture = mapper.readTree(Path.of(System.getProperty("example.insights")).toFile());
            assertEquals("Local receipt", fixture.path("subject").asText());
            assertEquals("success@simulator.amazonses.com", fixture.path("insights").get(0).path("destination").asText());
            String rawId = Files.readString(Path.of(System.getProperty("example.rawMessageId"))).trim();
            assertEquals("sender@example.test", ses.getMessageInsights(r -> r.messageId(rawId)).fromEmailAddress());

            String id = ses.sendEmail(r -> r.fromEmailAddress("sender@example.test")
                .destination(d -> d.toAddresses("bounce@simulator.amazonses.com"))
                .configurationSetName("example-mail")
                .content(c -> c.simple(m -> m.subject(s -> s.data("SDK receipt").charset("UTF-8"))
                    .body(b -> b.text(t -> t.data("Order 43 was received.").charset("UTF-8")))))).messageId();
            assertNotNull(id);
            var accepted = ses.getMessageInsights(r -> r.messageId(id));
            assertEquals("SDK receipt", accepted.subject());
            assertEquals("bounce@simulator.amazonses.com", accepted.insights().get(0).destination());
            // Insights prove acceptance, not real delivery, and do not include message bodies.
            assertEquals(EventType.SEND, accepted.insights().get(0).events().get(0).type());

            String queue = sqs.getQueueUrl(r -> r.queueName("example-mail-events")).queueUrl();
            Set<String> observed = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!observed.containsAll(List.of("Send", "Bounce")) && System.nanoTime() < deadline) {
                for (var message : sqs.receiveMessage(r -> r.queueUrl(queue).maxNumberOfMessages(10).waitTimeSeconds(1)).messages()) {
                    JsonNode event = mapper.readTree(message.body());
                    if (id.equals(event.path("mail").path("messageId").asText())) {
                        observed.add(event.path("eventType").asText());
                        assertEquals("SDK receipt", event.path("mail").path("commonHeaders").path("subject").asText());
                    }
                    sqs.deleteMessage(r -> r.queueUrl(queue).receiptHandle(message.receiptHandle()));
                }
            }
            assertTrue(observed.containsAll(List.of("Send", "Bounce")), "Missing simulated events: " + observed);
            assertThrows(MessageRejectedException.class, () -> ses.sendEmail(r -> r.fromEmailAddress("unknown@example.test")
                .destination(d -> d.toAddresses("success@simulator.amazonses.com"))
                .content(c -> c.simple(m -> m.subject(s -> s.data("Unverified sender"))
                    .body(b -> b.text(t -> t.data("Must fail")))))));
            assertFalse(Boolean.getBoolean("example.fail"), "Requested example failure");
        }
    }
}
