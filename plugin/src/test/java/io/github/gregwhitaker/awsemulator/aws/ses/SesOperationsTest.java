package io.github.gregwhitaker.awsemulator.aws.ses;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class SesOperationsTest {
    private final SesOperations operations = new SesOperations();

    @Test void identityCreationHandlesConcurrentCreationAndWaitsForVerification() {
        var reads = new AtomicInteger();
        var sdk = client(SesV2Client.class, (p, method, args) -> switch (method.getName()) {
            case "getEmailIdentity" -> {
                if (reads.getAndIncrement() == 0) throw NotFoundException.builder().build();
                yield GetEmailIdentityResponse.builder().verifiedForSendingStatus(true).build();
            }
            case "createEmailIdentity" -> throw AlreadyExistsException.builder().build();
            default -> throw new AssertionError(method.getName());
        });
        operations.createIdentity(sdk, "sender@example.test", 1);
        assertEquals(2, reads.get());
    }

    @Test void reusesVerifiedIdentityAndExistingConfigurationWithoutModifyingThem() {
        var sdk = client(SesV2Client.class, (p, method, args) -> switch (method.getName()) {
            case "getEmailIdentity" -> GetEmailIdentityResponse.builder().verifiedForSendingStatus(true).build();
            case "getConfigurationSet" -> GetConfigurationSetResponse.builder()
                .sendingOptions(SendingOptions.builder().sendingEnabled(false).build()).build();
            default -> throw new AssertionError("Unexpected mutation: " + method.getName());
        });
        operations.createIdentity(sdk, "sender@example.test", 1);
        operations.createConfigurationSet(sdk, "mail");
    }

    @Test void templatesAreIdempotentAndConflictWithoutOverwrite() {
        var content = SesOperations.content("Hello {{name}}", "Welcome", "<p>Welcome</p>");
        var stored = new java.util.concurrent.atomic.AtomicReference<EmailTemplateContent>();
        var writes = new ArrayList<String>();
        var sdk = client(SesV2Client.class, (p, method, args) -> switch (method.getName()) {
            case "getEmailTemplate" -> {
                if (stored.get() == null) throw NotFoundException.builder().build();
                yield GetEmailTemplateResponse.builder().templateContent(stored.get()).build();
            }
            case "createEmailTemplate" -> {
                stored.set(((CreateEmailTemplateRequest) args[0]).templateContent()); writes.add("create");
                yield CreateEmailTemplateResponse.builder().build();
            }
            case "updateEmailTemplate" -> {
                stored.set(((UpdateEmailTemplateRequest) args[0]).templateContent()); writes.add("update");
                yield UpdateEmailTemplateResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        operations.putTemplate(sdk, "welcome", content, false);
        operations.putTemplate(sdk, "welcome", content, false);
        var changed = SesOperations.content("Hello {{name}}", "private-new-body", null);
        var conflict = assertThrows(GradleException.class, () -> operations.putTemplate(sdk, "welcome", changed, false));
        assertTrue(conflict.getMessage().contains("overwrite=true"));
        assertFalse(conflict.getMessage().contains("private-new-body"));
        assertEquals(List.of("create"), writes);
        operations.putTemplate(sdk, "welcome", changed, true);
        assertEquals(List.of("create", "update"), writes);
        assertEquals(changed, stored.get());
    }

    @Test void paginatesIdentityAndConfigurationListsAndRejectsRepeatedTokens() {
        var tokens = new ArrayList<String>();
        var sdk = client(SesV2Client.class, (p, method, args) -> {
            if (method.getName().equals("listEmailIdentities")) {
                String token = ((ListEmailIdentitiesRequest) args[0]).nextToken(); tokens.add(token);
                return ListEmailIdentitiesResponse.builder().emailIdentities(IdentityInfo.builder()
                    .identityName(token == null ? "one@example.test" : "two@example.test").build())
                    .nextToken(token == null ? "next" : null).build();
            }
            return ListConfigurationSetsResponse.builder().configurationSets("mail").nextToken("repeated").build();
        });
        assertEquals(List.of("one@example.test", "two@example.test"), operations.identities(sdk).stream().map(IdentityInfo::identityName).toList());
        assertEquals(2, tokens.size());
        assertNull(tokens.get(0)); assertEquals("next", tokens.get(1));
        assertThrows(GradleException.class, () -> operations.configurationSets(sdk));
    }

    @Test void destinationReuseIgnoresEventOrderButRequiresOverwriteForChanges() {
        String arn = "arn:aws:sns:us-east-1:000000000000:mail";
        var changed = new AtomicBoolean();
        var sdk = client(SesV2Client.class, (p, method, args) -> switch (method.getName()) {
            case "getConfigurationSetEventDestinations" -> GetConfigurationSetEventDestinationsResponse.builder()
                .eventDestinations(EventDestination.builder().name("events").enabled(true)
                    .matchingEventTypes(EventType.SEND, EventType.DELIVERY)
                    .snsDestination(SnsDestination.builder().topicArn(arn).build()).build()).build();
            case "updateConfigurationSetEventDestination" -> {
                var request = (UpdateConfigurationSetEventDestinationRequest) args[0];
                assertEquals(List.of("BOUNCE"), request.eventDestination().matchingEventTypesAsStrings());
                changed.set(true); yield UpdateConfigurationSetEventDestinationResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        operations.putSnsDestination(sdk, "mail", "events", arn, List.of("DELIVERY", "SEND"), false);
        assertFalse(changed.get());
        assertThrows(GradleException.class, () -> operations.putSnsDestination(sdk, "mail", "events", arn, List.of("BOUNCE"), false));
        operations.putSnsDestination(sdk, "mail", "events", arn, List.of("BOUNCE"), true);
        assertTrue(changed.get());
    }

    @Test void deletionsIgnoreOnlyMissingResourcesAndErrorsDoNotExposeContent() {
        var missing = client(SesV2Client.class, (p, method, args) -> { throw NotFoundException.builder().build(); });
        operations.deleteIdentity(missing, "sender@example.test");
        operations.deleteTemplate(missing, "welcome");
        operations.deleteConfigurationSet(missing, "mail");
        var denied = client(SesV2Client.class, (p, method, args) -> { throw BadRequestException.builder().build(); });
        assertThrows(BadRequestException.class, () -> operations.deleteIdentity(denied, "sender@example.test"));
        var error = SesV2Exception.builder().message("private message content")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build()).build();
        var safe = SesOperations.failure("Sending", error);
        assertTrue(safe.getMessage().contains("MessageRejected"));
        assertFalse(safe.getMessage().contains("private message content"));
        assertNull(safe.getCause());
    }
}
