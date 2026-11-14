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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects a named stack and bounds its lifecycle wait. */
@DisableCachingByDefault(because = "Uses live emulator stacks")
public abstract class AbstractCloudFormationStackTask extends AbstractAwsResourceTask {
    public AbstractCloudFormationStackTask() { getWaitTimeoutSeconds().convention(120); }

    /** Stack name, beginning with a letter and containing up to 128 letters, digits, or hyphens. */
    @Input public abstract Property<String> getStackName();
    /** Deadline for stack creation, update, or deletion. Defaults to 120 seconds. */
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();

    @Option(option = "stackName", description = "CloudFormation stack name")
    public void setStackNameOption(String name) { getStackName().set(name); }

    protected final String stackName() {
        ServiceSupport.positive(getWaitTimeoutSeconds().get());
        return ServiceSupport.name(getStackName().get(), "[A-Za-z][A-Za-z0-9-]{0,127}", "CloudFormation stackName");
    }
}
