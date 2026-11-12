package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Operates on resources in the selected AWS Emulator instance. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteEventBusTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        if (bus.equals("default")) throw new GradleException("The default event bus cannot be deleted.");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            try {
                if (!new EventBridgeOperations().rules(client, bus).isEmpty()) throw new GradleException("Delete the bus's rules explicitly before deleting the bus.");
                client.deleteEventBus(software.amazon.awssdk.services.eventbridge.model.DeleteEventBusRequest.builder().name(bus).build());
            } catch (software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException ignored) { }
        }
    }
}
