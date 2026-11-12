package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.Arrays;
import java.util.List;

/** Shared explicit bucket selection for bulk S3 operations. */
@DisableCachingByDefault(because = "Operates on live emulator buckets")
public abstract class AbstractS3BucketsTask extends AbstractAwsResourceTask {
    @Input
    public abstract ListProperty<String> getBuckets();

    @Option(option = "buckets", description = "Comma-delimited bucket names")
    public void setBucketsOption(String names) {
        getBuckets().set(Arrays.stream(names.split(",", -1)).map(String::trim).toList());
    }

    protected final List<String> bucketNames() {
        List<String> names = getBuckets().get();
        if (names.isEmpty()) throw new GradleException("buckets must contain at least one bucket name.");
        names.forEach(S3BucketProvisioner::validate);
        return names.stream().distinct().toList();
    }
}
