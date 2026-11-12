package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.eventbridge.model.DescribeRuleRequest;
import java.util.Map;

/** Resolves a standard SQS queue, grants the selected rule permission, and registers a stable target ID. */
@DisableCachingByDefault(because = "Configures live emulator resources")
public abstract class PutEventBridgeSqsTargetTask extends AbstractAwsResourceTask {
    public PutEventBridgeSqsTargetTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getEventBusName();
    @Input public abstract Property<String> getRuleName();
    @Input public abstract Property<String> getQueueName();
    @Input public abstract Property<String> getTargetId();
    @Input public abstract Property<Boolean> getOverwrite();

    @TaskAction public void configureTarget() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        String queue = ServiceSupport.name(getQueueName().get(), "[A-Za-z0-9_-]{1,80}", "standard queueName");
        String id = ServiceSupport.name(getTargetId().get(), "[A-Za-z0-9_.-]{1,64}", "targetId");
        var clients = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get());
        try (var events = clients.eventBridge(); var sqs = clients.sqs()) {
            String arn = events.describeRule(DescribeRuleRequest.builder().eventBusName(bus).name(rule).build()).arn();
            String url = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queue).build()).queueUrl();
            var attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN, QueueAttributeName.POLICY).build()).attributes();
            String queueArn = attrs.get(QueueAttributeName.QUEUE_ARN);
            String policy = policy(attrs.get(QueueAttributeName.POLICY), arn, queueArn);
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(url).attributes(Map.of(QueueAttributeName.POLICY, policy)).build());
            new EventBridgeOperations().putTargets(events, bus, rule, Map.of(id, queueArn), getOverwrite().get());
        }
    }

    static String policy(String existing, String ruleArn, String queueArn) {
        try {
            var mapper = new ObjectMapper();
            ObjectNode policy = existing == null || existing.isBlank() ? mapper.createObjectNode()
                : (ObjectNode) mapper.readTree(JsonFixtures.object(existing, "queue policy"));
            if (!policy.has("Version")) policy.put("Version", "2012-10-17");
            var statements = mapper.createArrayNode();
            var previous = policy.get("Statement");
            if (previous != null && !previous.isArray() && !previous.isObject()) throw new GradleException("Queue policy Statement must be an object or array.");
            String sid = "AwsEmulatorEventBridge" + io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings.hash(ruleArn);
            var grant = mapper.createObjectNode();
            grant.put("Sid", sid); grant.put("Effect", "Allow"); grant.putObject("Principal").put("Service", "events.amazonaws.com");
            grant.put("Action", "sqs:SendMessage"); grant.put("Resource", queueArn);
            grant.putObject("Condition").putObject("ArnEquals").put("aws:SourceArn", ruleArn);
            if (previous != null) {
                var items = previous.isArray() ? previous : mapper.createArrayNode().add(previous);
                for (var statement : items) {
                    if (!sid.equals(statement.path("Sid").asText())) statements.add(statement);
                    else if (!statement.equals(grant)) throw new GradleException("Queue policy has a conflicting statement for the selected rule.");
                }
            }
            statements.add(grant); policy.set("Statement", statements);
            return policy.toString();
        } catch (GradleException e) { throw e; }
        catch (Exception e) { throw new GradleException("Cannot update the SQS queue policy."); }
    }
}
