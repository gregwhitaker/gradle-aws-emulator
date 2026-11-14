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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorSupportTest {
    @TempDir Path directory;

    @Test
    void generatesPinnedImagesForBothDaemonArchitectures() {
        assertTrue(EmulatorImage.dockerfile("1.0.322", EmulatorImage.platform("aarch64"))
            .contains("aws-emulator-1.0.322-1-aarch64.rpm"));
        assertTrue(EmulatorImage.dockerfile("1.0.322", EmulatorImage.platform("x86_64"))
            .contains("aws-emulator-1.0.322-1-x86_64.rpm"));
        assertThrows(IllegalArgumentException.class, () -> EmulatorImage.platform("ppc64le"));
        assertThrows(IllegalArgumentException.class, () -> EmulatorImage.dockerfile("1.0.322", "windows/amd64"));
        assertThrows(IllegalArgumentException.class, () -> EmulatorImage.dockerfile("latest\nRUN false", "linux/amd64"));
    }

    @Test
    void readsOnlyExplicitDefaultCredentialsIncludingSessionToken() throws Exception {
        Path file = directory.resolve("credentials");
        Files.writeString(file, "[default]\naws_access_key_id = generated-key\naws_secret_access_key = generated-secret\n"
            + "aws_session_token = generated-token\n[other]\naws_access_key_id = other-key\naws_secret_access_key = other-secret\n");
        var credentials = assertInstanceOf(AwsSessionCredentials.class, EmulatorCredentials.read(file));
        assertEquals("generated-key", credentials.accessKeyId());
        assertEquals("generated-secret", credentials.secretAccessKey());
        assertEquals("generated-token", credentials.sessionToken());
    }

    @Test
    void redactsMalformedCredentialsAndNeverFallsBack() throws Exception {
        Path file = directory.resolve("credentials");
        Files.writeString(file, "[default]\nprivate-secret-malformed-line\n");
        var failure = assertThrows(IllegalArgumentException.class, () -> EmulatorCredentials.read(file));
        assertFalse(failure.toString().contains("private-secret"));
        assertNull(failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> EmulatorCredentials.read(directory.resolve("missing")));
    }
}
