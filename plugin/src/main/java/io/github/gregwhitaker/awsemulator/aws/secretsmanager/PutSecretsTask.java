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

/** Loads Secrets Manager fixtures from a JSON array; validates every entry before provisioning. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutSecretsTask extends AbstractAwsResourceTask {
    public PutSecretsTask() { getOverwrite().convention(false); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getFixtures();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        var entries = JsonFixtures.read(getFixtures().get().getAsFile().toPath(), java.util.Set.of("name", "value", "keyId")).stream()
            .map(row -> new SecretOperations.Secret(JsonFixtures.string(row, "name", null), JsonFixtures.string(row, "value", null), JsonFixtures.optional(row, "keyId"))).toList();
        ServiceSupport.names(entries.stream().map(SecretOperations.Secret::name).toList(), SecretOperations.NAME, "fixture names");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).secretsManager()) {
            int completed = 0;
            for (var entry : entries) {
                try { new SecretOperations().put(client, entry, getOverwrite().get()); completed++; }
                catch (RuntimeException e) { throw new GradleException("Provisioning stopped at '" + entry.name() + "' after " + completed + " completed entries; rerun after resolving the failure.", e); }
            }
            getLogger().lifecycle("Prepared {} Secrets Manager resources.", completed);
        }
    }
}
