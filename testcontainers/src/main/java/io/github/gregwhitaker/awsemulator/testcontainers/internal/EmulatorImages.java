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

package io.github.gregwhitaker.awsemulator.testcontainers.internal;

import com.github.dockerjava.api.exception.NotFoundException;
import io.github.gregwhitaker.awsemulator.runtime.EmulatorImage;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.LazyFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Future;

/** Resolves the daemon platform and builds only when Testcontainers starts the container. */
public final class EmulatorImages {
    private EmulatorImages() {}

    public static Future<String> forVersion(String version) {
        EmulatorImage.validateVersion(version);
        return new LazyFuture<>() {
            @Override
            protected String resolve() {
                var docker = DockerClientFactory.instance().client();
                String platform = EmulatorImage.platform(docker.infoCmd().exec().getArchitecture());
                String dockerfile = EmulatorImage.dockerfile(version, platform);
                try {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(dockerfile.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
                    String image = "testcontainers-aws-emulator:" + version + "-" + platform.substring(6) + "-" + hash;
                    try {
                        docker.inspectImageCmd(image).exec();
                        return image;
                    } catch (NotFoundException missing) {
                        // Retain the pinned image for later test runs; containers remain disposable.
                        return new ImageFromDockerfile(image, false)
                            .withFileFromString("Dockerfile", dockerfile)
                            .withBuildImageCmdModifier(command -> command.withPlatform(platform)
                                .withRemove(true).withForcerm(true)).get();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot prepare the AWS Emulator image for " + platform, e);
                }
            }
        };
    }
}
