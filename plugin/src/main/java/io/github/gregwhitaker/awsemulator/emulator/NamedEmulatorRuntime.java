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

import org.gradle.api.GradleException;
import org.gradle.api.Named;
import javax.inject.Inject;

/**
 * A named runtime with its own managed and development instances, or one external connection.
 * Common settings inherit from awsEmulator; port defaults to zero, the working directory is
 * isolated by name, and external connection settings are configured independently.
 */
public abstract class NamedEmulatorRuntime extends EmulatorRuntimeConfiguration implements Named {
    private final String name;

    @Inject public NamedEmulatorRuntime(String name) {
        if (name.equals(AwsEmulatorExtension.DEFAULT_RUNTIME)) {
            throw new GradleException("The runtime name 'default' is reserved. Configure it directly in awsEmulator.");
        }
        if (!name.matches("[a-z][a-zA-Z0-9]*(?:-[a-zA-Z0-9]+)*")) {
            throw new GradleException("Runtime names must start with a lowercase letter and contain letters, numbers, or single hyphens.");
        }
        this.name = name;
    }

    @Override public String getName() { return name; }

    static String taskSuffix(String name) {
        if (name.equals(AwsEmulatorExtension.DEFAULT_RUNTIME)) return "";
        StringBuilder suffix = new StringBuilder();
        for (String part : name.split("-")) suffix.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return suffix.toString();
    }
}
