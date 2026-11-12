package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Lists object keys and sizes, following every response page. */
@DisableCachingByDefault(because = "Reads live emulator objects")
public abstract class ListS3ObjectsTask extends AbstractS3ObjectTask {
    public ListS3ObjectsTask() { getPrefix().convention(""); }
    @Input public abstract Property<String> getPrefix();
    @Option(option = "prefix", description = "Only list keys beginning with this prefix")
    public void setPrefixOption(String prefix) { getPrefix().set(prefix); }

    @TaskAction public void list() {
        String bucket = bucketName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var objects = new S3ObjectOperations().list(client, bucket, getPrefix().get());
            if (objects.isEmpty()) getLogger().lifecycle("No S3 objects found in {}", bucket);
            objects.forEach(object -> getLogger().lifecycle("{} ({} bytes)", object.key(), object.size()));
        }
    }
}
