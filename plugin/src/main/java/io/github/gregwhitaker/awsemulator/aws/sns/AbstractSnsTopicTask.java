package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared topic selection for SNS tasks. Topic ARNs are resolved from the emulator. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSnsTopicTask extends AbstractAwsResourceTask {
    @Input public abstract Property<String> getTopicName();

    @Option(option = "topicName", description = "SNS topic name")
    public void setTopicNameOption(String value) { getTopicName().set(value); }

    protected final String topicName() {
        String name = getTopicName().get();
        SnsTopicOperations.validateName(name);
        return name;
    }
}
