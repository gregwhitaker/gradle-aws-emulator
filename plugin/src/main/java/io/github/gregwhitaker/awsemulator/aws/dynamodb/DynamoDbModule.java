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
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers the DynamoDB commands and configures custom tasks in the same service group. */
public final class DynamoDbModule {
    private static final String GROUP_NAME = "AWS Emulator - DynamoDB";

    private DynamoDbModule() {}

    public static void load(Project project) {
        configure(project, CreateDynamoDbTableTask.class, "Creates or reuses a DynamoDB table in AWS Emulator.");
        configure(project, DeleteDynamoDbTableTask.class, "Deletes a DynamoDB table from AWS Emulator.");
        configure(project, ListDynamoDBTablesTask.class, "Lists DynamoDB tables in AWS Emulator.");
        configure(project, PutDynamoDbItemsTask.class, "Loads JSON item fixtures into a DynamoDB table in AWS Emulator.");
        configure(project, PurgeDynamoDbTableTask.class, "Removes all items while preserving a DynamoDB table in AWS Emulator.");

        project.getTasks().register("purgeDynamoDbTable", PurgeDynamoDbTableTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));

        project.getTasks().register("deleteDynamoDbTable", DeleteDynamoDbTableTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("listDynamoDbTables", ListDynamoDBTablesTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup(GROUP_NAME);
            task.setDescription(description);
        });
    }
}
