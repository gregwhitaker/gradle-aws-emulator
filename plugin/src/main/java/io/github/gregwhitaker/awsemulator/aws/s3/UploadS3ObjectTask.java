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
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import java.util.Map;

/** Uploads one file, replacing the object at the selected key. */
@DisableCachingByDefault(because = "Writes live emulator objects")
public abstract class UploadS3ObjectTask extends AbstractS3ObjectTask {
    public UploadS3ObjectTask() { getMetadata().convention(Map.of()); }

    @Input public abstract Property<String> getObjectKey();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getSourceFile();
    /** Inferred from the extension when omitted; otherwise application/octet-stream. */
    @Input @Optional public abstract Property<String> getContentType();
    @Input public abstract MapProperty<String, String> getMetadata();

    @TaskAction public void upload() {
        String bucket = bucketName();
        String key = S3ObjectOperations.key(getObjectKey().get());
        var source = getSourceFile().get().getAsFile().toPath();
        String type = getContentType().getOrElse(S3ObjectOperations.contentType(source, Map.of()));
        S3ObjectOperations.validateContentType(type);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            new S3ObjectOperations().upload(client, bucket, new S3ObjectOperations.Upload(source, key, type), getMetadata().get());
        }
        getLogger().lifecycle("Uploaded S3 object: s3://{}/{}", bucket, key);
    }
}
