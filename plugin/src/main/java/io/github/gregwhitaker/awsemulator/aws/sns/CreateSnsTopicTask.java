package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Creates or reuses a standard or FIFO topic, verifying explicitly requested attributes. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
public abstract class CreateSnsTopicTask extends AbstractSnsTopicTask {
    public CreateSnsTopicTask() { getAttributes().convention(Map.of()); }

    /** AWS SNS attribute names and string values. FIFO topics require FifoTopic=true. */
    @Input public abstract MapProperty<String, String> getAttributes();

    @TaskAction
    public void createTopic() {
        String name = topicName();
        var attributes = getAttributes().get();
        SnsTopicOperations.validate(name, attributes);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sns()) {
            String arn = new SnsTopicOperations().provision(client, name, attributes);
            getLogger().lifecycle("SNS topic ready: {} ({})", name, arn);
        }
    }
}
