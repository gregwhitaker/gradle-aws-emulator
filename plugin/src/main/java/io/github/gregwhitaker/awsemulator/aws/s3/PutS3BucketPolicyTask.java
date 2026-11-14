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
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Replaces a bucket policy with a JSON document; existing statements are not merged. */
@DisableCachingByDefault(because = "Configures live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutS3BucketPolicyTask extends AbstractS3BucketTask {
    /** Inline JSON policy. Set exactly one of policy and policyFile. */
    @Input @Optional public abstract Property<String> getPolicy();
    /** UTF-8 JSON policy, read at execution time. Set exactly one of policy and policyFile. */
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getPolicyFile();

    @Option(option = "policyFile", description = "UTF-8 JSON policy file, relative to this project")
    public void setPolicyFileOption(String value) { getPolicyFile().set(getProject().file(value)); }

    @TaskAction
    public void putPolicy() {
        String bucket = bucketName();
        if (getPolicy().isPresent() == getPolicyFile().isPresent()) {
            throw new GradleException("Set exactly one of policy and policyFile.");
        }
        String policy = getPolicyFile().isPresent()
            ? S3BucketConfiguration.policyFile(getPolicyFile().get().getAsFile().toPath())
            : S3BucketConfiguration.policy(getPolicy().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            client.putBucketPolicy(request -> request.bucket(bucket).policy(policy));
        }
        getLogger().lifecycle("Replaced S3 bucket policy: {}", bucket);
    }
}
