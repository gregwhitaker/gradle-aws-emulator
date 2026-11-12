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

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Archives logs and removes stale managed containers owned by the selected runtime's project. */
@DisableCachingByDefault(because = "Manages live Docker and emulator state")
public abstract class CleanupAwsEmulatorTask extends AbstractAwsEmulatorTask {
    public CleanupAwsEmulatorTask() {
        getReportOnly().convention(false);
    }

    /** When true, lists eligible containers without removing them. Defaults to false. */
    @Input
    @Option(option = "report-only", description = "List stale managed containers without removing them.")
    public abstract Property<Boolean> getReportOnly();

    @TaskAction
    public void cleanup() {
        getLogger().lifecycle(getRuntime().get().cleanup(getReportOnly().get()));
    }
}
