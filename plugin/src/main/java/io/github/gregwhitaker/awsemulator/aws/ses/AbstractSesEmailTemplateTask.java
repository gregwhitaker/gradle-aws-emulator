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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an SES template in the task's runtime. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSesEmailTemplateTask extends AbstractAwsResourceTask {
    /** Exact SES template name. */
    @Input public abstract Property<String> getTemplateName();

    @Option(option = "templateName", description = "Exact SES template name")
    public void setTemplateNameOption(String value) { getTemplateName().set(value); }

    protected final String templateName() { return SesOperations.name(getTemplateName().get(), "templateName"); }
}
