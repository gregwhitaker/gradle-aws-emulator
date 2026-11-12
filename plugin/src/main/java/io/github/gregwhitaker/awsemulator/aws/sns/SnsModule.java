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
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Configures SNS task types and development commands without starting the emulator. */
public final class SnsModule {
    private SnsModule() {}

    public static void load(Project project) {
        configure(project, CreateSnsTopicTask.class, "Creates or reuses an SNS topic in AWS Emulator.");
        configure(project, CreateSnsTopicWithSqsEndpointTask.class, "Creates an SNS topic with a subscribed SQS queue in AWS Emulator.");
        configure(project, ListSnsTopicsTask.class, "Lists SNS topics in AWS Emulator.");
        configure(project, ListSnsTopicSubscriptionsTask.class, "Lists subscriptions for an SNS topic in AWS Emulator.");
        configure(project, DeleteSnsTopicTask.class, "Deletes an SNS topic and its subscriptions in AWS Emulator.");
        configure(project, PublishSnsTask.class, "Publishes a message to an SNS topic in AWS Emulator.");

        project.getTasks().register("listSnsTopics", ListSnsTopicsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("listSnsTopicSubscriptions", ListSnsTopicSubscriptionsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteSnsTopic", DeleteSnsTopicTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("publishSns", PublishSnsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup("AWS Emulator - SNS");
            task.setDescription(description);
        });
    }
}
