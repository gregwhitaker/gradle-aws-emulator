package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Requests removal of all messages from the selected queues without deleting them. */
@DisableCachingByDefault(because = "Modifies live emulator resources")
public abstract class PurgeSqsQueuesTask extends AbstractSqsQueuesTask {
    @TaskAction
    public void purgeQueues() {
        var names = queueNames();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            new SqsQueueOperations().purge(client, names);
            names.forEach(name -> getLogger().lifecycle("SQS purge requested: {}", name));
        }
    }
}
