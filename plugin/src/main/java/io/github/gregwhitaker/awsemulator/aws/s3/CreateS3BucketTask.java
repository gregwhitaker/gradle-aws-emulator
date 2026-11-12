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

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates or reuses a general-purpose bucket in AWS Emulator. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateS3BucketTask extends AbstractAwsResourceTask {
    @Input
    public abstract Property<String> getBucketName();

    @TaskAction
    public void createBucket() {
        String name = getBucketName().get();
        S3BucketProvisioner.validate(name);
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).s3()) {
            new S3BucketProvisioner().provision(client, name, connection.region());
        }
        getLogger().lifecycle("S3 bucket ready: {} ({})", name, connection.endpoint());
    }
}
