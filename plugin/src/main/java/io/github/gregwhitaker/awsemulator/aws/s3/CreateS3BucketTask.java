package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates or reuses a general-purpose bucket in AWS Emulator. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
public abstract class CreateS3BucketTask extends AbstractAwsResourceTask {
    @Input
    public abstract Property<String> getBucketName();

    @TaskAction
    public void createBucket() {
        String name = getBucketName().get();
        S3BucketProvisioner.validate(name);
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).s3()) {
            new S3BucketProvisioner().provision(client, name, connection.region());
        }
        getLogger().lifecycle("S3 bucket ready: {} ({})", name, connection.endpoint());
    }
}
