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

package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared table selection and resource-wait settings for create and delete operations. */
@DisableCachingByDefault(because = "Operates on live emulator tables")
public abstract class AbstractDynamoDbTableTask extends AbstractAwsResourceTask {
    public AbstractDynamoDbTableTask() { getWaitTimeoutSeconds().convention(60); }

    @Input
    public abstract Property<String> getTableName();

    /** Deadline for waiting for ACTIVE or for deletion to complete. Defaults to 60 seconds. */
    @Input
    public abstract Property<Integer> getWaitTimeoutSeconds();

    @Option(option = "tableName", description = "Name of the DynamoDB table")
    public void setTableNameOption(String name) { getTableName().set(name); }

    protected final String tableName() {
        String name = getTableName().get();
        DynamoDbTableDefinition.validateName(name);
        if (getWaitTimeoutSeconds().get() < 1) throw new GradleException("waitTimeoutSeconds must be positive.");
        return name;
    }
}
