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

/** Adds standard SQS targets by ID and ARN; unrelated target IDs are preserved. Queue policies are configured separately. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PutEventBridgeTargetsTask extends AbstractAwsResourceTask {
    public PutEventBridgeTargetsTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @Input public abstract Property<String> getRuleName();
    @Option(option = "ruleName", description = "RuleName") public void setRuleNameOption(String value) { getRuleName().set(value); }
    @Input public abstract MapProperty<String, String> getTargets();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        var targets = getTargets().get();
        if (targets.isEmpty()) throw new GradleException("targets must not be empty.");
        targets.forEach((id, arn) -> {
            ServiceSupport.name(id, "[A-Za-z0-9_.-]{1,64}", "target ID");
            ServiceSupport.name(arn, "arn:[^:]+:sqs:[^:]+:[0-9]{12}:[A-Za-z0-9_-]{1,80}", "standard SQS target ARN");
        });
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().putTargets(client, bus, rule, targets, getOverwrite().get());
        }
    }
}
