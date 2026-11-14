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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

/** Enables or suspends bucket versioning without changing existing object versions or MFA Delete. */
@DisableCachingByDefault(because = "Configures live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutS3BucketVersioningTask extends AbstractS3BucketTask {
    /** Required desired state: ENABLED or SUSPENDED. There is no implicit default. */
    @Input public abstract Property<BucketVersioningStatus> getStatus();

    @Option(option = "status", description = "Bucket versioning state: ENABLED or SUSPENDED")
    public void setStatusOption(BucketVersioningStatus value) { getStatus().set(value); }

    @TaskAction
    public void putVersioning() {
        String bucket = bucketName();
        var status = S3BucketConfiguration.versioning(getStatus().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            client.putBucketVersioning(request -> request.bucket(bucket).versioningConfiguration(config -> config.status(status)));
        }
        getLogger().lifecycle("Set S3 bucket versioning: {} ({})", bucket, status);
    }
}
