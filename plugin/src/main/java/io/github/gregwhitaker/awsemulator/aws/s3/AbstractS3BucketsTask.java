/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
