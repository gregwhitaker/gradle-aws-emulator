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

import javax.net.ssl.TrustManager;
import java.nio.file.Path;

/** Shared per-client emulator trust, with no changes to JVM-wide TLS settings. */
public final class EmulatorTls {
    private EmulatorTls() {}
    public static TrustManager[] trustManagers(Path trustStore) throws Exception {
        return io.github.gregwhitaker.awsemulator.runtime.EmulatorTls.trustManagers(trustStore);
    }
}
