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

package io.github.gregwhitaker.awsemulator;

import io.github.gregwhitaker.awsemulator.aws.AwsModule;
import io.github.gregwhitaker.awsemulator.emulator.EmulatorModule;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.util.GradleVersion;

/** Entry point for io.github.gregwhitaker.aws-emulator. */
public class AwsEmulatorPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        if (GradleVersion.current().compareTo(GradleVersion.version("8.14")) < 0) {
            throw new GradleException("AWS Emulator requires Gradle 8.14 or newer and Java 17 or newer.");
        }
        EmulatorModule.load(project);
        AwsModule.load(project);
    }
}
