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

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Settings for connecting to an existing AWS Emulator without owning its lifecycle. */
public abstract class ExternalEmulatorConnection {
    public ExternalEmulatorConnection() { getEnabled().convention(false); }

    /** Whether to use the external connection. The external configuration block enables it by default. */
    public abstract Property<Boolean> getEnabled();
    /** Absolute HTTPS endpoint, including an optional port, without a path, query, or credentials. */
    public abstract Property<String> getEndpoint();
    /** Existing AWS shared credentials file containing the emulator's default profile. Read only at execution time. */
    public abstract RegularFileProperty getCredentialsFile();
    /** Existing PEM CA certificate bundle used to verify the endpoint's certificate and hostname. */
    public abstract RegularFileProperty getCertificateFile();
}
