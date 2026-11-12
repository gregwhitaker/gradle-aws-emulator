package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates a queue and its DLQ, applying the source queue's requested attributes and redrive policy. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
public abstract class CreateSqsQueueWithDlqTask extends AbstractAwsResourceTask {
    public CreateSqsQueueWithDlqTask() {
        getQueueAttributes().convention(Map.of());
        getDeadletterQueueAttributes().convention(Map.of());
        getDeadletterQueueName().convention(getQueueName().map(SqsDeadLetterQueueProvisioner::defaultName));
        getMaxReceiveCount().convention(1);
    }

    @Input public abstract Property<String> getQueueName();
    @Input public abstract MapProperty<String, String> getQueueAttributes();
    @Input public abstract Property<String> getDeadletterQueueName();
    @Input public abstract MapProperty<String, String> getDeadletterQueueAttributes();
    @Input public abstract Property<Integer> getMaxReceiveCount();

    @TaskAction
    public void createQueueWithDlq() {
        var settings = new SqsDeadLetterQueueProvisioner.Settings(getQueueName().get(), getQueueAttributes().get(),
            getDeadletterQueueName().get(), getDeadletterQueueAttributes().get(), getMaxReceiveCount().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            new SqsDeadLetterQueueProvisioner().provision(client, settings);
        }
        getLogger().lifecycle("SQS queue ready: {} (DLQ: {})", settings.name(), settings.dlqName());
    }
}
