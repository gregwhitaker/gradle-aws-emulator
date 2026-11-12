package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists all subscriptions for an existing topic. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListSnsTopicSubscriptionsTask extends AbstractSnsTopicTask {
    @TaskAction
    public void listSubscriptions() {
        String name = topicName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            var operations = new SnsTopicOperations();
            var subscriptions = operations.subscriptions(client, operations.require(client, name));
            getLogger().lifecycle("SubscriptionArn\tProtocol\tEndpoint");
            subscriptions.forEach(subscription -> getLogger().lifecycle("{}\t{}\t{}",
                subscription.subscriptionArn(), subscription.protocol(), subscription.endpoint()));
            if (subscriptions.isEmpty()) getLogger().lifecycle("No Subscriptions Found!");
        }
    }
}
