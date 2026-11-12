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
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Removes objects, versions, and delete markers while retaining the selected buckets. */
@DisableCachingByDefault(because = "Modifies live emulator resources")
public abstract class PurgeS3BucketsTask extends AbstractS3BucketsTask {
    @TaskAction
    public void purgeBuckets() {
        var names = bucketNames();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var operations = new S3BucketOperations();
            for (String name : names) {
                operations.purge(client, name);
                getLogger().lifecycle("Purged S3 bucket: {}", name);
            }
        }
    }
}
