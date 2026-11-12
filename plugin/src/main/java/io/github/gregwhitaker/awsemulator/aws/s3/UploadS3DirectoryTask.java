package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Recursively uploads files using relative paths as keys; unrelated objects are preserved. */
@DisableCachingByDefault(because = "Writes live emulator objects")
public abstract class UploadS3DirectoryTask extends AbstractS3ObjectTask {
    public UploadS3DirectoryTask() {
        getPrefix().convention("");
        getContentTypes().convention(Map.of());
        getMetadata().convention(Map.of());
    }
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSourceDirectory();
    @Input public abstract Property<String> getPrefix();
    /** MIME overrides keyed by lowercase extension without a dot, for example json. */
    @Input public abstract MapProperty<String, String> getContentTypes();
    /** User metadata applied to every uploaded object. */
    @Input public abstract MapProperty<String, String> getMetadata();

    @TaskAction public void upload() {
        String bucket = bucketName();
        var operations = new S3ObjectOperations();
        var uploads = operations.directory(getSourceDirectory().get().getAsFile().toPath(), getPrefix().get(), getContentTypes().get());
        if (!uploads.isEmpty()) {
            try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
                for (var upload : uploads) operations.upload(client, bucket, upload, getMetadata().get());
            }
        }
        getLogger().lifecycle("Uploaded {} S3 objects to {}", uploads.size(), bucket);
    }
}
