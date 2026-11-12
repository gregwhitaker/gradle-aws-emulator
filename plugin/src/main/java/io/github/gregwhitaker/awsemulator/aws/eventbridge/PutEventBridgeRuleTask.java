package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
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

/** Creates an enabled event-pattern rule; conflicting rules require overwrite=true. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutEventBridgeRuleTask extends AbstractAwsResourceTask {
    public PutEventBridgeRuleTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @Input public abstract Property<String> getRuleName();
    @Option(option = "ruleName", description = "RuleName") public void setRuleNameOption(String value) { getRuleName().set(value); }
    @Input public abstract Property<String> getEventPattern();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        String pattern = JsonFixtures.object(getEventPattern().get(), "eventPattern");
        if (pattern.length() > 4096) throw new GradleException("eventPattern exceeds 4096 characters.");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().putRule(client, bus, rule, pattern, getOverwrite().get());
        }
    }
}
