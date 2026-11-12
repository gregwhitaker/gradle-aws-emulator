package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared table selection and resource-wait settings for create and delete operations. */
@DisableCachingByDefault(because = "Operates on live emulator tables")
public abstract class AbstractDynamoDbTableTask extends AbstractAwsResourceTask {
    public AbstractDynamoDbTableTask() { getWaitTimeoutSeconds().convention(60); }

    @Input
    public abstract Property<String> getTableName();

    /** Deadline for waiting for ACTIVE or for deletion to complete. Defaults to 60 seconds. */
    @Input
    public abstract Property<Integer> getWaitTimeoutSeconds();

    @Option(option = "tableName", description = "Name of the DynamoDB table")
    public void setTableNameOption(String name) { getTableName().set(name); }

    protected final String tableName() {
        String name = getTableName().get();
        DynamoDbTableDefinition.validateName(name);
        if (getWaitTimeoutSeconds().get() < 1) throw new GradleException("waitTimeoutSeconds must be positive.");
        return name;
    }
}
