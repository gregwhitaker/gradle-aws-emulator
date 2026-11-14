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

package io.github.gregwhitaker.awsemulator.runtime;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/** Loads only the emulator trust roots; never changes JVM-wide SSL configuration. */
public final class EmulatorTls {
    private EmulatorTls() {}

    /** Loads certificate trust from an emulator-generated PKCS12 store without changing global TLS settings. */
    public static TrustManager[] trustManagers(Path trustStore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(trustStore)) { keys.load(input, null); }
        if (keys.size() == 0) {
            try (InputStream input = Files.newInputStream(trustStore)) { keys.load(input, new char[0]); }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keys);
        return factory.getTrustManagers();
    }
}
