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

package io.github.gregwhitaker.awsemulator.aws;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Shared configuration for AWS service tasks inside this project's emulator. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractAwsResourceTask extends AbstractAwsEmulatorTask {
    public AbstractAwsResourceTask() {
        getMode().convention(EmulatorMode.MANAGED);
        getApiCallTimeoutSeconds().convention(30);
        getOutputs().upToDateWhen(ignored -> false);
        getOutputs().doNotCacheIf("Uses live emulator resources", ignored -> true);
    }

    /** Selects the lifecycle mode within runtimeName. Custom instances default to MANAGED; convenience tasks use DEVELOPMENT. */
    @Input
    public abstract Property<EmulatorMode> getMode();

    @Option(option = "mode", description = "Emulator runtime: MANAGED or DEVELOPMENT")
    public void setModeOption(EmulatorMode mode) { getMode().set(mode); }

    /** Deadline for each SDK operation, including retries. Defaults to 30 seconds. */
    @Input
    public abstract Property<Integer> getApiCallTimeoutSeconds();

    protected final EmulatorConnection connection() {
        if (getApiCallTimeoutSeconds().get() < 1) throw new GradleException("apiCallTimeoutSeconds must be positive.");
        return getMode().get() == EmulatorMode.MANAGED
            ? getRuntime().get().managedConnection() : getRuntime().get().developmentConnection();
    }
}
