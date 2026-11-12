package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists all topic names and ARNs, following every response page. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListSnsTopicsTask extends AbstractAwsResourceTask {
    @TaskAction
    public void listTopics() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            var topics = new SnsTopicOperations().list(client);
            getLogger().lifecycle("TopicName\tTopicArn");
            topics.forEach(topic -> getLogger().lifecycle("{}\t{}", SnsTopicOperations.name(topic.topicArn()), topic.topicArn()));
            if (topics.isEmpty()) getLogger().lifecycle("No Topics Found!");
        }
    }
}
