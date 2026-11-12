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

package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

/** Stores a template; replacing different content requires overwrite=true. Does not render or send it. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PutSesEmailTemplateTask extends AbstractSesEmailTemplateTask {
    public PutSesEmailTemplateTask() { getOverwrite().convention(false); }

    /** Subject template, including any SES substitution placeholders. */
    @Input public abstract Property<String> getSubject();
    /** UTF-8 plain text template; at least one body part is required. */
    @Input @Optional public abstract Property<String> getTextBody();
    /** UTF-8 HTML template; at least one body part is required. */
    @Input @Optional public abstract Property<String> getHtmlBody();
    /** Allows replacing different stored content. Identical content is always reused. */
    @Input public abstract Property<Boolean> getOverwrite();

    @TaskAction public void put() {
        String name = templateName();
        var content = SesOperations.content(getSubject().get(), getTextBody().getOrNull(), getHtmlBody().getOrNull());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().putTemplate(client, name, content, getOverwrite().get());
            getLogger().lifecycle("SES template ready: {}", name);
        } catch (SdkException error) { throw SesOperations.failure("Storing SES template", error); }
    }
}
