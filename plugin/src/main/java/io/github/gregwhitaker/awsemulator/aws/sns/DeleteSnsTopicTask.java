package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Deletes a topic and its subscriptions. Subscribed queues are retained; absent topics succeed. */
@DisableCachingByDefault(because = "Deletes live emulator resources")
public abstract class DeleteSnsTopicTask extends AbstractSnsTopicTask {
    @TaskAction
    public void deleteTopic() {
        String name = topicName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            new SnsTopicOperations().delete(client, name);
            getLogger().lifecycle("SNS topic absent: {}", name);
        }
    }
}
