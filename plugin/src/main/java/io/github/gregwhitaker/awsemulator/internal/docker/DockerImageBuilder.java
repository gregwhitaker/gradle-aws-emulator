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

package io.github.gregwhitaker.awsemulator.internal.docker;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorWorkspace;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prepares and reuses a pinned emulator image for the selected Docker platform. */
public final class DockerImageBuilder {
    private final EmulatorSettings settings;
    private final EmulatorWorkspace workspace;
    private final DockerClient docker;
    private Image prepared;

    public DockerImageBuilder(EmulatorSettings settings, EmulatorWorkspace workspace, DockerClient docker) {
        this.settings = settings;
        this.workspace = workspace;
        this.docker = docker;
    }

    public synchronized Image prepare() {
        if (prepared != null) return prepared;
        prepared = workspace.withLock("image.lock", () -> {
            String platform = docker.platform();
            if (!settings.image().isBlank()) {
                if (!docker.imageExists(settings.image())) docker.pullImage(settings.image(), platform);
                return new Image(settings.image(), platform);
            }
            String dockerfile = dockerfile(settings.version(), platform);
            String hash = EmulatorSettings.hash(dockerfile);
            String tag = reference(platform);
            if (!docker.imageExists(tag)) {
                Path context = workspace.imageDirectory(hash);
                try {
                    Files.createDirectories(context);
                    Files.writeString(context.resolve("Dockerfile"), dockerfile);
                } catch (IOException e) {
                    throw new GradleException("Cannot create AWS Emulator Docker build context.", e);
                }
                Logging.getLogger(DockerImageBuilder.class).lifecycle("Building AWS Emulator {} for {} (first build downloads the AWS package)", settings.version(), platform);
                docker.buildImage(tag, platform, context);
            }
            return new Image(tag, platform);
        });
        return prepared;
    }

    /** Computes the image reference without pulling or building it. */
    public String reference(String platform) {
        return settings.image().isBlank() ? "gradle-aws-emulator:" + settings.version() + "-" + platform.substring(6)
            + "-" + EmulatorSettings.hash(dockerfile(settings.version(), platform)) : settings.image();
    }

    public static String dockerfile(String version, String platform) {
        String architecture = platform.equals("linux/arm64") ? "aarch64" : "x86_64";
        return "FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal\n"
            + "ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/aws-emulator-" + version
            + "-1-" + architecture + ".rpm /tmp/aws-emulator.rpm\n"
            + "RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm "
            + "&& mkdir -p /home/emulator && chown 1000 /home/emulator\n"
            + "USER 1000\nENV HOME=/home/emulator\nEXPOSE 9297\n"
            + "ENTRYPOINT [\"aws-emulator\", \"--bind\", \"0.0.0.0\"]\n";
    }

    public record Image(String reference, String platform) {}
}
