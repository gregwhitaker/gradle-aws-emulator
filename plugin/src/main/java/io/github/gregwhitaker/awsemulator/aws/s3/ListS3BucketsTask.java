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
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists bucket names and creation dates, including every response page. */
@DisableCachingByDefault(because = "Reads live emulator state")
public abstract class ListS3BucketsTask extends AbstractAwsResourceTask {
    @TaskAction
    public void listBuckets() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var buckets = new S3BucketOperations().list(client);
            getLogger().lifecycle("BucketName\tCreationDate");
            buckets.forEach(bucket -> getLogger().lifecycle("{}\t{}", bucket.name(), bucket.creationDate()));
            if (buckets.isEmpty()) getLogger().lifecycle("No Buckets Found!");
        }
    }
}
