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

/** Removes targets from the selected rule, then deletes the rule. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteEventBridgeRuleTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @Input public abstract Property<String> getRuleName();
    @Option(option = "ruleName", description = "RuleName") public void setRuleNameOption(String value) { getRuleName().set(value); }
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().deleteRule(client, bus, rule);
        }
    }
}
