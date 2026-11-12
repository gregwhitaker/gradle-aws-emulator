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

package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers CloudWatch Logs tasks without starting the emulator. */
public final class CloudWatchLogsModule {
    private CloudWatchLogsModule() {}
    public static void load(Project project) {
        configure(project, CreateLogGroupTask.class);
        configure(project, CreateLogStreamTask.class);
        configure(project, ListLogGroupsTask.class);
        configure(project, DeleteLogGroupTask.class);
        configure(project, DeleteLogStreamTask.class);
        configure(project, GetLogEventsTask.class);
        configure(project, FilterLogEventsTask.class);
        project.getTasks().register("listLogGroups", ListLogGroupsTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteLogGroup", DeleteLogGroupTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteLogStream", DeleteLogStreamTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - CloudWatch Logs");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
