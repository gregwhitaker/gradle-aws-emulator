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

package io.github.gregwhitaker.awsemulator.testsupport;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorRuntime;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import java.nio.file.Path;

/** An emulator owned by the enclosing test, independent of the consumer Gradle builds under test. */
public final class ExternalEmulatorFixture implements AutoCloseable {
    private final EmulatorRuntime runtime;
    private final EmulatorConnection connection;

    public ExternalEmulatorFixture(Path directory) {
        runtime = new EmulatorRuntime(new EmulatorSettings(EmulatorSettings.hash(directory.toAbsolutePath().toString()),
            "1.0.322", "", "auto", 0, "us-east-1", false, "docker", "2g", 60, 60, 600, directory));
        try { connection = runtime.managedConnection(); }
        catch (RuntimeException e) { runtime.close(); throw e; }
    }

    public EmulatorConnection connection() { return connection; }
    @Override public void close() { runtime.close(); }
}
