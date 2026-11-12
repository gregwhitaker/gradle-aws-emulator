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

package io.github.gregwhitaker.awsemulator.aws.kms;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Decrypts a local binary fixture with a symmetric KMS key, writing the result to a separate file. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DecryptKmsFileTask extends AbstractAwsResourceTask {
    public DecryptKmsFileTask() { getEncryptionContext().convention(Map.of()); }
    @Input public abstract Property<String> getKeyId();
    @Option(option = "keyId", description = "KeyId") public void setKeyIdOption(String value) { getKeyId().set(value); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getInputFile();
    @OutputFile public abstract RegularFileProperty getOutputFile();
    @Input public abstract MapProperty<String, String> getEncryptionContext();
    @TaskAction
    public void executeTask() {
        String key = ServiceSupport.text(getKeyId().get(), "keyId");
        var output = getOutputFile().get().getAsFile().toPath();
        byte[] input = ResourceFiles.read(getInputFile().get().getAsFile().toPath(), output, 6144);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            var response = client.decrypt(software.amazon.awssdk.services.kms.model.DecryptRequest.builder().keyId(key)
                .ciphertextBlob(software.amazon.awssdk.core.SdkBytes.fromByteArray(input)).encryptionContext(getEncryptionContext().get()).build());
            ResourceFiles.write(output, response.plaintext().asByteArray());
        }
    }
}
