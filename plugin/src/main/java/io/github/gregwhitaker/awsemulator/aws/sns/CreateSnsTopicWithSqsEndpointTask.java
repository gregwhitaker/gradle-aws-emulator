package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates or reuses a topic and queue, grants delivery permission, and subscribes the queue. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSnsTopicWithSqsEndpointTask extends AbstractSnsTopicTask {
    public CreateSnsTopicWithSqsEndpointTask() {
        getTopicAttributes().convention(Map.of());
        getQueueAttributes().convention(Map.of());
        getSubscriptionAttributes().convention(Map.of());
        getQueueName().convention(getTopicName().map(SnsSqsSubscriptionProvisioner::defaultQueueName));
    }

    @Input public abstract MapProperty<String, String> getTopicAttributes();

    /** Defaults to topicName + "-subscriber", before the .fifo suffix for FIFO topics. */
    @Input public abstract Property<String> getQueueName();

    /** Requested SQS attributes, excluding Policy. FifoQueue=true is inferred for .fifo queues. */
    @Input public abstract MapProperty<String, String> getQueueAttributes();

    /** SNS subscription attributes, such as RawMessageDelivery and FilterPolicy. */
    @Input public abstract MapProperty<String, String> getSubscriptionAttributes();

    @TaskAction
    public void createTopicWithSqsEndpoint() {
        var settings = new SnsSqsSubscriptionProvisioner.Settings(topicName(), getTopicAttributes().get(),
            getQueueName().get(), getQueueAttributes().get(), getSubscriptionAttributes().get());
        var clients = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get());
        try (var sns = clients.sns(); var sqs = clients.sqs()) {
            String arn = new SnsSqsSubscriptionProvisioner().provision(sns, sqs, settings);
            getLogger().lifecycle("SNS topic ready: {} (SQS queue: {}, subscription: {})", settings.topicName(), settings.queueName(), arn);
        }
    }
}
