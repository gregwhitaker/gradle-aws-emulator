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

package io.github.gregwhitaker.awsemulator.core;

import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorService;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared service binding for tasks that consume this project's emulator. */
@DisableCachingByDefault(because = "Operates on a live emulator runtime")
public abstract class AbstractAwsEmulatorTask extends DefaultTask {
    public AbstractAwsEmulatorTask() { getRuntimeName().convention("default"); }

    /** Named runtime to use, or default for the top-level awsEmulator settings. */
    @Input
    public abstract Property<String> getRuntimeName();

    @Option(option = "runtime", description = "Named AWS Emulator runtime (default: default)")
    public void setRuntimeNameOption(String name) { getRuntimeName().set(name); }

    @Internal
    public abstract Property<AwsEmulatorService> getRuntime();
}
