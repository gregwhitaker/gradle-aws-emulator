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

package io.github.gregwhitaker.awsemulator.emulator;

import org.gradle.api.Project;
import java.util.Locale;

/** Configures report tasks lazily, including custom reports attached to test finalizers. */
final class ReportModule {
    private ReportModule() {}

    static void load(Project project) {
        project.getTasks().withType(ReportAwsEmulatorTask.class).configureEach(task -> {
            task.setGroup("AWS Emulator");
            task.setDescription("Writes HTML and JSON inventories of an existing AWS Emulator.");
            String taskName = task.getName();
            task.getReportDirectory().convention(project.getLayout().getBuildDirectory().dir(
                task.getRuntimeName().zip(task.getMode(), (runtime, mode) ->
                    "reports/aws-emulator/" + runtime + "/" + mode.name().toLowerCase(Locale.ROOT) + "/" + taskName)));
        });
    }
}
