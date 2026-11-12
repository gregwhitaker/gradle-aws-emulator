package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Deletes selected buckets; nonempty buckets require force. */
@DisableCachingByDefault(because = "Deletes live emulator resources")
public abstract class DeleteS3BucketsTask extends AbstractS3BucketsTask {
    public DeleteS3BucketsTask() { getForce().convention(false); }

    @Input
    public abstract Property<Boolean> getForce();

    @Option(option = "force", description = "Remove objects, versions, and delete markers before deleting buckets")
    public void setForceOption(boolean force) { getForce().set(force); }

    @TaskAction
    public void deleteBuckets() {
        var names = bucketNames();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var operations = new S3BucketOperations();
            for (String name : names) {
                operations.delete(client, name, getForce().get());
                getLogger().lifecycle("Deleted S3 bucket: {}", name);
            }
        }
    }
}
