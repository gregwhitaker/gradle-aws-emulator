package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Lists queue names, URLs, and approximate available message counts. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListSqsQueuesTask extends AbstractAwsResourceTask {
    @Input @Optional
    public abstract Property<String> getPrefix();

    @Option(option = "prefix", description = "Only list queues whose names start with this prefix")
    public void setPrefixOption(String prefix) { getPrefix().set(prefix); }

    @TaskAction
    public void listQueues() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sqs()) {
            var queues = new SqsQueueOperations().list(client, getPrefix().getOrNull());
            getLogger().lifecycle("QueueName\tQueueUrl\tApproximateNumberOfMessages");
            queues.forEach(queue -> getLogger().lifecycle("{}\t{}\t{}", queue.name(), queue.url(), queue.messages()));
            if (queues.isEmpty()) getLogger().lifecycle("No Queues Found!");
        }
    }
}
