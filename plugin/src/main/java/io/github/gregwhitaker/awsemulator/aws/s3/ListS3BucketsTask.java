package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists bucket names and creation dates, including every response page. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListS3BucketsTask extends AbstractAwsResourceTask {
    @TaskAction
    public void listBuckets() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var buckets = new S3BucketOperations().list(client);
            getLogger().lifecycle("BucketName\tCreationDate");
            buckets.forEach(bucket -> getLogger().lifecycle("{}\t{}", bucket.name(), bucket.creationDate()));
            if (buckets.isEmpty()) getLogger().lifecycle("No Buckets Found!");
        }
    }
}
