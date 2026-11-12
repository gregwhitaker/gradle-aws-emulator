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
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Reports Docker, image, port, runtime, connection-file, and HTTPS health diagnostics without starting an instance. */
@DisableCachingByDefault(because = "Inspects live Docker and emulator state")
public abstract class DoctorAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void diagnose() {
        getLogger().lifecycle(getRuntime().get().doctor());
    }
}
