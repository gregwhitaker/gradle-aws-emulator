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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Deletes selected buckets; nonempty buckets require force. */
@DisableCachingByDefault(because = "Deletes live emulator resources")
public abstract class DeleteS3BucketsTask extends AbstractS3BucketsTask {
    public DeleteS3BucketsTask() { getForce().convention(false); }

    @Input
    public abstract Property<Boolean> getForce();

    @Option(option = "force", description = "Remove objects, versions, and delete markers before deleting buckets")
    public void setForceOption(boolean force) { getForce().set(force); }

    @TaskAction
    public void deleteBuckets() {
        var names = bucketNames();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var operations = new S3BucketOperations();
            for (String name : names) {
                operations.delete(client, name, getForce().get());
                getLogger().lifecycle("Deleted S3 bucket: {}", name);
            }
        }
    }
}
