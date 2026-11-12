package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates or reuses multiple general-purpose buckets. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateS3BucketsTask extends AbstractS3BucketsTask {
    @TaskAction
    public void createBuckets() {
        var names = bucketNames();
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).s3()) {
            var provisioner = new S3BucketProvisioner();
            for (String name : names) {
                provisioner.provision(client, name, connection.region());
                getLogger().lifecycle("S3 bucket ready: {} ({})", name, connection.endpoint());
            }
        }
    }
}
