package io.github.gregwhitaker.awsemulator.aws.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.ResourceFiles;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.model.GetMessageInsightsRequest;

/** Exports accepted-message metadata and per-recipient events. SES insights do not expose email bodies. */
@DisableCachingByDefault(because = "Reads live emulator messages")
public abstract class GetSesMessageInsightsTask extends AbstractAwsResourceTask {
    /** ID returned by SendEmail in the selected runtime. */
    @Input public abstract Property<String> getMessageId();
    /** JSON output; defaults to build/aws-emulator/ses/TASK/insights.json. */
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @Option(option = "messageId", description = "SES message ID in the selected runtime")
    public void setMessageIdOption(String value) { getMessageId().set(value); }

    @TaskAction public void export() {
        String id = ServiceSupport.text(getMessageId().get(), "messageId");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            var response = client.getMessageInsights(GetMessageInsightsRequest.builder().messageId(id).build());
            var mapper = new ObjectMapper();
            var json = mapper.createObjectNode();
            json.put("messageId", response.messageId());
            json.put("fromEmailAddress", response.fromEmailAddress());
            json.put("subject", response.subject());
            var tags = json.putArray("emailTags");
            response.emailTags().forEach(tag -> tags.addObject().put("name", tag.name()).put("value", tag.value()));
            var insights = json.putArray("insights");
            response.insights().forEach(insight -> {
                var entry = insights.addObject().put("destination", insight.destination()).put("isp", insight.isp());
                var events = entry.putArray("events");
                insight.events().forEach(event -> {
                    var item = events.addObject().put("type", event.typeAsString());
                    if (event.timestamp() != null) item.put("timestamp", event.timestamp().toString());
                });
            });
            try { ResourceFiles.write(getOutputFile().get().getAsFile().toPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json)); }
            catch (java.io.IOException error) { throw new GradleException("Cannot serialize SES message insights.", error); }
        } catch (SdkException error) { throw SesOperations.failure("Reading SES message insights", error); }
    }
}
