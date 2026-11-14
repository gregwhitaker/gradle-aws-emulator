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
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists non-deleted stacks and their current statuses. */
@DisableCachingByDefault(because = "Reads live emulator stacks")
public abstract class ListCloudFormationStacksTask extends AbstractAwsResourceTask {
    @TaskAction public void list() {
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).cloudFormation()) {
            for (var stack : new CloudFormationOperations().list(client)) {
                getLogger().lifecycle("{} ({})", stack.stackName(), stack.stackStatusAsString());
            }
        }
    }
}
