package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Table selection and retry settings for DynamoDB batch writes. */
@DisableCachingByDefault(because = "Operates on live emulator items")
public abstract class AbstractDynamoDbItemsTask extends AbstractAwsResourceTask {
    public AbstractDynamoDbItemsTask() {
        getMaxRetries().convention(8);
        getRetryDelayMillis().convention(100);
    }
    @Input public abstract Property<String> getTableName();
    /** Retries for unprocessed items in each batch, in addition to SDK request retries. */
    @Input public abstract Property<Integer> getMaxRetries();
    /** Initial exponential backoff; the delay is capped at 2 seconds. */
    @Input public abstract Property<Integer> getRetryDelayMillis();

    @Option(option = "tableName", description = "Name of the DynamoDB table")
    public void setTableNameOption(String name) { getTableName().set(name); }

    protected final String tableName() {
        String name = getTableName().get();
        DynamoDbTableDefinition.validateName(name);
        if (getMaxRetries().get() < 0) throw new GradleException("maxRetries must not be negative.");
        if (getRetryDelayMillis().get() < 1) throw new GradleException("retryDelayMillis must be positive.");
        return name;
    }
}
