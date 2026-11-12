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
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Downloads one object to a file, replacing the destination only after a successful download. */
@DisableCachingByDefault(because = "Reads live emulator objects")
public abstract class DownloadS3ObjectTask extends AbstractS3ObjectTask {
    @Input public abstract Property<String> getObjectKey();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void download() {
        String bucket = bucketName();
        String key = S3ObjectOperations.key(getObjectKey().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            new S3ObjectOperations().download(client, bucket, key, getOutputFile().get().getAsFile().toPath());
        }
        getLogger().lifecycle("Downloaded S3 object: s3://{}/{}", bucket, key);
    }
}
