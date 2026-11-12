package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects a bucket for object operations. The bucket must already exist. */
@DisableCachingByDefault(because = "Operates on live emulator objects")
public abstract class AbstractS3ObjectTask extends AbstractAwsResourceTask {
    @Input public abstract Property<String> getBucketName();

    @Option(option = "bucketName", description = "Name of the S3 bucket")
    public void setBucketNameOption(String name) { getBucketName().set(name); }

    protected final String bucketName() {
        String name = getBucketName().get();
        S3BucketProvisioner.validate(name);
        return name;
    }
}
