package io.github.gregwhitaker.awsemulator.aws.sqs;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.Arrays;
import java.util.List;

/** Shared explicit queue selection for bulk SQS operations. */
@DisableCachingByDefault(because = "Operates on live emulator queues")
public abstract class AbstractSqsQueuesTask extends AbstractAwsResourceTask {
    @Input
    public abstract ListProperty<String> getQueueNames();

    @Option(option = "queueNames", description = "Comma-delimited queue names")
    public void setQueueNamesOption(String names) {
        getQueueNames().set(Arrays.stream(names.split(",", -1)).map(String::trim).toList());
    }

    protected final List<String> queueNames() {
        var names = getQueueNames().get();
        if (names.isEmpty()) throw new GradleException("queueNames must contain at least one queue name.");
        names.forEach(SqsQueueProvisioner::validateName);
        return names.stream().distinct().toList();
    }
}
