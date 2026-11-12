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
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Creates or reuses multiple general-purpose buckets. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateS3BucketsTask extends AbstractS3BucketsTask {
    @TaskAction
    public void createBuckets() {
        var names = bucketNames();
        var connection = connection();
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).s3()) {
            var provisioner = new S3BucketProvisioner();
            for (String name : names) {
                provisioner.provision(client, name, connection.region());
                getLogger().lifecycle("S3 bucket ready: {} ({})", name, connection.endpoint());
            }
        }
    }
}
