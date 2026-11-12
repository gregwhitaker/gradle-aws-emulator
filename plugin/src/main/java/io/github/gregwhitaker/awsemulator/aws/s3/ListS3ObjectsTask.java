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

/** Lists object keys and sizes, following every response page. */
@DisableCachingByDefault(because = "Reads live emulator objects")
public abstract class ListS3ObjectsTask extends AbstractS3ObjectTask {
    public ListS3ObjectsTask() { getPrefix().convention(""); }
    @Input public abstract Property<String> getPrefix();
    @Option(option = "prefix", description = "Only list keys beginning with this prefix")
    public void setPrefixOption(String prefix) { getPrefix().set(prefix); }

    @TaskAction public void list() {
        String bucket = bucketName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            var objects = new S3ObjectOperations().list(client, bucket, getPrefix().get());
            if (objects.isEmpty()) getLogger().lifecycle("No S3 objects found in {}", bucket);
            objects.forEach(object -> getLogger().lifecycle("{} ({} bytes)", object.key(), object.size()));
        }
    }
}
