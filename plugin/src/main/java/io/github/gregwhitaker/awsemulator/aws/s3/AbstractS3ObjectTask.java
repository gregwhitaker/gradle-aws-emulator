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
