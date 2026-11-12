package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** SES provisioning and reads; no SMTP delivery or global SDK configuration. */
final class SesOperations {
    static String name(String value, String label) {
        return ServiceSupport.name(value, "[a-zA-Z0-9_-]{1,64}", label);
    }

    static String identity(String value) {
        ServiceSupport.text(value, "emailIdentity");
        if (value.contains("@")) return address(value, "emailIdentity");
        if (value.length() > 253 || !value.matches("[a-zA-Z0-9.-]+") || !value.contains(".")) {
            throw new GradleException("emailIdentity must be a bare email address or domain name.");
        }
        for (String label : value.split("\\.", -1)) {
            if (!label.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")) {
                throw new GradleException("emailIdentity must be a bare email address or domain name.");
            }
        }
        return value;
    }

    static String address(String value, String label) {
        if (value == null || value.length() > 254 || !value.matches("[^\\s@<>]+@[^\\s@<>]+")
                || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new GradleException(label + " must be a bare ASCII email address.");
        }
        return value;
    }

    static EmailTemplateContent content(String subject, String text, String html) {
        ServiceSupport.text(subject, "subject");
        body(text, html);
        long bytes = subject.getBytes(StandardCharsets.UTF_8).length
            + (text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length)
            + (html == null ? 0 : html.getBytes(StandardCharsets.UTF_8).length);
        if (bytes > 500_000) throw new GradleException("Email template content must not exceed 500000 UTF-8 bytes.");
        return EmailTemplateContent.builder().subject(subject).text(text).html(html).build();
    }

    static void body(String text, String html) {
        if ((text == null || text.isEmpty()) && (html == null || html.isEmpty())) {
            throw new GradleException("At least one of textBody or htmlBody must be nonempty.");
        }
    }

    void createIdentity(SesV2Client client, String identity, int timeout) {
        ServiceSupport.positive(timeout);
        try { client.getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(identity).build()); }
        catch (NotFoundException missing) {
            try { client.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(identity).build()); }
            catch (AlreadyExistsException raced) { /* A concurrent setup created the same identity. */ }
        }
        ServiceSupport.await("waiting for SES identity verification", timeout,
            () -> Boolean.TRUE.equals(client.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(identity).build()).verifiedForSendingStatus()));
    }

    List<IdentityInfo> identities(SesV2Client client) {
        List<IdentityInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listEmailIdentities(ListEmailIdentitiesRequest.builder().pageSize(1000).nextToken(token).build());
            result.addAll(page.emailIdentities()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    void deleteIdentity(SesV2Client client, String identity) {
        try { client.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(identity).build()); }
        catch (NotFoundException ignored) { }
    }

    void putTemplate(SesV2Client client, String name, EmailTemplateContent content, boolean overwrite) {
        EmailTemplateContent existing = null;
        try { existing = client.getEmailTemplate(GetEmailTemplateRequest.builder().templateName(name).build()).templateContent(); }
        catch (NotFoundException ignored) { }
        if (existing == null) {
            client.createEmailTemplate(CreateEmailTemplateRequest.builder().templateName(name).templateContent(content).build());
        } else if (!existing.equals(content)) {
            if (!overwrite) throw new GradleException("SES template '" + name + "' differs; set overwrite=true to replace it (content omitted).");
            client.updateEmailTemplate(UpdateEmailTemplateRequest.builder().templateName(name).templateContent(content).build());
        }
    }

    void deleteTemplate(SesV2Client client, String name) {
        try { client.deleteEmailTemplate(DeleteEmailTemplateRequest.builder().templateName(name).build()); }
        catch (NotFoundException ignored) { }
    }

    void createConfigurationSet(SesV2Client client, String name) {
        try { client.getConfigurationSet(GetConfigurationSetRequest.builder().configurationSetName(name).build()); }
        catch (NotFoundException missing) {
            try { client.createConfigurationSet(CreateConfigurationSetRequest.builder().configurationSetName(name).build()); }
            catch (AlreadyExistsException raced) { /* Leave an existing configuration unchanged. */ }
        }
    }

    List<String> configurationSets(SesV2Client client) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listConfigurationSets(ListConfigurationSetsRequest.builder().pageSize(1000).nextToken(token).build());
            result.addAll(page.configurationSets()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    void deleteConfigurationSet(SesV2Client client, String name) {
        try { client.deleteConfigurationSet(DeleteConfigurationSetRequest.builder().configurationSetName(name).build()); }
        catch (NotFoundException ignored) { }
    }

    static List<String> eventTypes(List<String> types) {
        if (types.isEmpty() || new HashSet<>(types).size() != types.size()
                || types.stream().anyMatch(type -> EventType.fromValue(type) == EventType.UNKNOWN_TO_SDK_VERSION)) {
            throw new GradleException("matchingEventTypes must contain distinct SES event types such as SEND, DELIVERY, BOUNCE, or COMPLAINT.");
        }
        return List.copyOf(types);
    }

    void putSnsDestination(SesV2Client client, String set, String name, String arn, List<String> types, boolean overwrite) {
        var existing = client.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
            .configurationSetName(set).build()).eventDestinations().stream().filter(d -> name.equals(d.name())).findFirst().orElse(null);
        var destination = EventDestinationDefinition.builder().enabled(true).matchingEventTypesWithStrings(types)
            .snsDestination(SnsDestination.builder().topicArn(arn).build()).build();
        if (existing == null) {
            client.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(set).eventDestinationName(name).eventDestination(destination).build());
        } else {
            boolean same = Boolean.TRUE.equals(existing.enabled()) && Objects.equals(existing.snsDestination(), destination.snsDestination())
                && new HashSet<>(existing.matchingEventTypesAsStrings()).equals(new HashSet<>(types))
                && existing.cloudWatchDestination() == null && existing.eventBridgeDestination() == null
                && existing.kinesisFirehoseDestination() == null && existing.pinpointDestination() == null;
            if (same) return;
            if (!overwrite) throw new GradleException("SES event destination '" + name + "' differs; set overwrite=true to replace it.");
            client.updateConfigurationSetEventDestination(UpdateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(set).eventDestinationName(name).eventDestination(destination).build());
        }
    }

    /** Service errors can contain email content; report only the code, without a cause carrying the payload. */
    static GradleException failure(String operation, SdkException error) {
        String code = error instanceof SesV2Exception service && service.awsErrorDetails() != null
            ? service.awsErrorDetails().errorCode() : error.getClass().getSimpleName();
        return new GradleException(operation + " failed (" + code + "; email content omitted).");
    }
}
