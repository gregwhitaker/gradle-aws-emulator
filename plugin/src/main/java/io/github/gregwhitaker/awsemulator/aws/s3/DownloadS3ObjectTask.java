package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Downloads one object to a file, replacing the destination only after a successful download. */
@DisableCachingByDefault(because = "Reads live emulator objects")
public abstract class DownloadS3ObjectTask extends AbstractS3ObjectTask {
    @Input public abstract Property<String> getObjectKey();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void download() {
        String bucket = bucketName();
        String key = S3ObjectOperations.key(getObjectKey().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            new S3ObjectOperations().download(client, bucket, key, getOutputFile().get().getAsFile().toPath());
        }
        getLogger().lifecycle("Downloaded S3 object: s3://{}/{}", bucket, key);
    }
}
