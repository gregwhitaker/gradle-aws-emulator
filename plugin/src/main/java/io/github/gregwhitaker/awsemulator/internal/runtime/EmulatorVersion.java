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

package io.github.gregwhitaker.awsemulator.internal.runtime;

import java.io.IOException;
import java.util.Properties;

/** Reads the tested default bundled from the repository's shared emulator manifest. */
public final class EmulatorVersion {
    public static final String DEFAULT = readDefault();

    private EmulatorVersion() {}

    private static String readDefault() {
        try (var input = EmulatorVersion.class.getResourceAsStream("emulator.properties")) {
            if (input == null) throw new IllegalStateException("Missing bundled AWS Emulator version.");
            var properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("emulatorVersion", "");
            if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
                throw new IllegalStateException("Invalid bundled AWS Emulator version.");
            }
            return version;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read bundled AWS Emulator version.", error);
        }
    }
}
