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
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/**
 * Exports current S3 object bytes, keys, content types and user metadata as a versioned JSON fixture.
 * Requires an existing runtime and bucket; never starts an owned runtime. Excludes bucket configuration,
 * historical versions and delete markers. The 16 MiB artifact can be replayed with ReplayS3ObjectsTask.
 */
@DisableCachingByDefault(because = "Reads live emulator objects")
public abstract class ExportS3ObjectsTask extends AbstractS3BucketTask {
    public ExportS3ObjectsTask() { getPrefix().convention(""); }

    /** Literal key prefix to export; empty selects all current objects in the explicit bucket. */
    @Input public abstract Property<String> getPrefix();
    /** Portable JSON fixture, atomically replaced only after all reads succeed. Contains object data. */
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void exportObjects() {
        String bucket = bucketName();
        if (getApiCallTimeoutSeconds().get() < 1) throw new GradleException("apiCallTimeoutSeconds must be positive.");
        var connection = getRuntime().get().inspectionConnection(getMode().get());
        try (var client = new AwsClientFactory(connection, getApiCallTimeoutSeconds().get()).s3()) {
            int count = new S3ObjectFixtures().export(client, bucket, getPrefix().get(), getOutputFile().get().getAsFile().toPath());
            getLogger().lifecycle("Exported {} S3 objects from {}", count, bucket);
        }
    }
}
