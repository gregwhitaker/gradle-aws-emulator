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

import org.gradle.api.GradleException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Immutable execution-time settings. Contains no credentials. */
public record EmulatorSettings(String owner, String version, String image, String platform, int port,
        String region, boolean telemetry, String dockerExecutable, String memoryLimit,
        int startupTimeout, int commandTimeout, int buildTimeout, Path workingDirectory) {
    public EmulatorSettings {
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (port < 0 || port > 65535) fail("port must be between 0 and 65535");
        if (!Set.of("auto", "linux/amd64", "linux/arm64").contains(platform)) {
            fail("platform must be auto, linux/amd64, or linux/arm64");
        }
        if (image.isBlank() && !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            fail("emulatorVersion must be a release version such as 1.0.322");
        }
        if (!region.matches("[a-z0-9-]+")) fail("region must contain lowercase letters, numbers, or hyphens");
        if (!memoryLimit.matches("[1-9][0-9]*[bkmgBKMG]?")) fail("memoryLimit must be a Docker memory size, such as 2g");
        if (startupTimeout < 1 || commandTimeout < 1 || buildTimeout < 1) fail("timeouts must be positive seconds");
        if (dockerExecutable.isBlank()) fail("dockerExecutable must not be empty");
        if (workingDirectory.toString().contains(",")) fail("workingDirectory cannot contain a comma (Docker mount syntax)");
        if (workingDirectory.toString().contains("\n") || workingDirectory.toString().contains("\r")) {
            fail("workingDirectory cannot contain line breaks");
        }
    }

    private static void fail(String message) { throw new GradleException("awsEmulator: " + message); }

    public String developmentName() { return "gradle-aws-emulator-" + owner; }

    public String fingerprint() {
        return hash(String.join("\n", version, image, platform, Integer.toString(port), region,
            Boolean.toString(telemetry), memoryLimit, workingDirectory.toString()));
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
