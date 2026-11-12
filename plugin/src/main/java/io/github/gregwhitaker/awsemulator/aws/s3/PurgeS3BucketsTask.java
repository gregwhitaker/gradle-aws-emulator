package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Removes objects, versions, and delete markers while retaining the selected buckets. */
@DisableCachingByDefault(because = "Modifies live emulator resources")
public abstract class PurgeS3BucketsTask extends AbstractS3BucketsTask {
    @TaskAction
    public void purgeBuckets() {
        var names = bucketNames();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var operations = new S3BucketOperations();
            for (String name : names) {
                operations.purge(client, name);
                getLogger().lifecycle("Purged S3 bucket: {}", name);
            }
        }
    }
}
