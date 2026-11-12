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

package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

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

/** Describes secret identity and deletion state without printing its value. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DescribeSecretTask extends AbstractAwsResourceTask {

    @Input public abstract Property<String> getSecretName();
    @Option(option = "secretName", description = "SecretName") public void setSecretNameOption(String value) { getSecretName().set(value); }
    @TaskAction
    public void executeTask() {
        ServiceSupport.name(getSecretName().get(), SecretOperations.NAME, "secretName");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            var entry = client.describeSecret(software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest.builder().secretId(getSecretName().get()).build());
            getLogger().lifecycle("{}\t{}\tDeletion date: {}", entry.name(), entry.arn(), entry.deletedDate());
        }
    }
}
