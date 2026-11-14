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
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/**
 * Replays a complete ExportS3ObjectsTask fixture into an explicitly selected existing bucket.
 * Validates the whole file before connecting, replaces matching keys, and preserves all other keys.
 * Writes are not transactional; cleanup followed by replay produces an exact current-object dataset.
 */
@DisableCachingByDefault(because = "Writes live emulator objects")
public abstract class ReplayS3ObjectsTask extends AbstractS3BucketTask {
    /** Version 1 S3 JSON fixture; maximum 16 MiB, including base64-encoded object data. */
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getObjectsFile();

    @TaskAction public void replayObjects() {
        String bucket = bucketName();
        var fixtures = new S3ObjectFixtures();
        var objects = fixtures.read(getObjectsFile().get().getAsFile().toPath());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            fixtures.replay(client, bucket, objects);
        }
        getLogger().lifecycle("Replayed {} S3 objects into {}", objects.size(), bucket);
    }
}
