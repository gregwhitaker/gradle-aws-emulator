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

/** Publishes JSON event fixtures and fails on partial service failures without replaying successful events. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PublishEventBridgeEventsTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFixtures();
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        var events = JsonFixtures.read(getFixtures().get().getAsFile().toPath(), java.util.Set.of("source", "detailType", "detail")).stream().map(row -> {
            String source = ServiceSupport.text(JsonFixtures.string(row, "source", null), "source");
            String type = ServiceSupport.text(JsonFixtures.string(row, "detailType", null), "detailType");
            if (source.length() > 256 || type.length() > 128) throw new GradleException("Event source or detailType is too long.");
            if (!row.has("detail") || !row.get("detail").isObject()) throw new GradleException("Event detail must be a JSON object.");
            String detail = row.get("detail").toString();
            if ((source + type + detail).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 128 >= 256 * 1024) throw new GradleException("Event fixture exceeds the supported 256 KiB entry limit.");
            return software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry.builder().eventBusName(bus).source(source).detailType(type).detail(detail).build();
        }).toList();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().publish(client, bus, events);
            getLogger().lifecycle("Published {} EventBridge events.", events.size());
        }
    }
}
