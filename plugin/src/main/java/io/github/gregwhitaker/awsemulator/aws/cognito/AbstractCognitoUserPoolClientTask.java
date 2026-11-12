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

package io.github.gregwhitaker.awsemulator.aws.cognito;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an app client by exact name within the selected Cognito User Pool. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractCognitoUserPoolClientTask extends AbstractCognitoUserPoolTask {
    /** Exact app client name. Multiple matching clients cause a failure. */
    @Input public abstract Property<String> getClientName();

    @Option(option = "clientName", description = "Exact Cognito app client name")
    public void setClientNameOption(String value) { getClientName().set(value); }

    protected final String clientName() { return CognitoOperations.name(getClientName().get(), "clientName"); }
}
