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

/** Docker image definition shared by Gradle and Testcontainers. Does not access Docker. */
public final class EmulatorImage {
    /** Pinned release used when no version is selected. */
    public static final String DEFAULT_VERSION = "1.0.322";
    /** HTTPS port inside the standalone container. */
    public static final int PORT = 9297;
    private EmulatorImage() {}

    /** Maps the Docker daemon's CPU architecture to a supported Linux platform. */
    public static String platform(String architecture) {
        return switch (architecture) {
            case "aarch64", "arm64" -> "linux/arm64";
            case "x86_64", "amd64" -> "linux/amd64";
            default -> throw new IllegalArgumentException("Unsupported Docker architecture: " + architecture);
        };
    }

    /** Validates a pinned emulator package version before it is used in a Dockerfile. */
    public static void validateVersion(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("AWS Emulator version must be a release version such as 1.0.322");
        }
    }

    /** Returns the Dockerfile for a pinned release on linux/amd64 or linux/arm64. */
    public static String dockerfile(String version, String platform) {
        validateVersion(version);
        if (!java.util.Set.of("linux/amd64", "linux/arm64").contains(platform)) {
            throw new IllegalArgumentException("Unsupported Docker platform: " + platform);
        }
        String architecture = platform.equals("linux/arm64") ? "aarch64" : "x86_64";
        return "FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal\n"
            + "ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/aws-emulator-" + version
            + "-1-" + architecture + ".rpm /tmp/aws-emulator.rpm\n"
            + "RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm "
            + "&& mkdir -p /home/emulator && chown 1000 /home/emulator\n"
            + "USER 1000\nENV HOME=/home/emulator\nEXPOSE 9297\n"
            + "ENTRYPOINT [\"aws-emulator\", \"--bind\", \"0.0.0.0\"]\n";
    }

}
