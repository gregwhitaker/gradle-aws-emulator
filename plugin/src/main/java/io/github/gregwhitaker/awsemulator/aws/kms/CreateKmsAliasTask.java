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

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
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

/** Operates on resources in the selected AWS Emulator instance. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class CreateKmsAliasTask extends AbstractAwsResourceTask {
    public CreateKmsAliasTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getAliasName();
    @Option(option = "aliasName", description = "AliasName") public void setAliasNameOption(String value) { getAliasName().set(value); }
    @Input public abstract Property<String> getKeyId();
    @Option(option = "keyId", description = "KeyId") public void setKeyIdOption(String value) { getKeyId().set(value); }
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String alias = KmsOperations.alias(getAliasName().get());
        String key = ServiceSupport.text(getKeyId().get(), "keyId");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            new KmsOperations().alias(client, alias, key, getOverwrite().get());
        }
    }
}
