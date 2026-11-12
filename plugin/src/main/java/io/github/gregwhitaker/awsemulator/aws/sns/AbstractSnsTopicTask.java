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

package io.github.gregwhitaker.awsemulator.aws.sns;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared topic selection for SNS tasks. Topic ARNs are resolved from the emulator. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSnsTopicTask extends AbstractAwsResourceTask {
    @Input public abstract Property<String> getTopicName();

    @Option(option = "topicName", description = "SNS topic name")
    public void setTopicNameOption(String value) { getTopicName().set(value); }

    protected final String topicName() {
        String name = getTopicName().get();
        SnsTopicOperations.validateName(name);
        return name;
    }
}
