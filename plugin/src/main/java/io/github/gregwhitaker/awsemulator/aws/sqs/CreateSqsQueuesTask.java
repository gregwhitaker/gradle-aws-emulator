package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates or reuses multiple queues with common attributes. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateSqsQueuesTask extends AbstractSqsQueuesTask {
    public CreateSqsQueuesTask() { getQueueAttributes().convention(Map.of()); }

    @Input
    public abstract MapProperty<String, String> getQueueAttributes();

    @TaskAction
    public void createQueues() {
        var names = queueNames();
        var attributes = getQueueAttributes().get();
        names.forEach(name -> SqsQueueProvisioner.validate(name, attributes));
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            var provisioner = new SqsQueueProvisioner();
            for (String name : names) {
                provisioner.provision(client, name, attributes);
                getLogger().lifecycle("SQS queue ready: {}", name);
            }
        }
    }
}
