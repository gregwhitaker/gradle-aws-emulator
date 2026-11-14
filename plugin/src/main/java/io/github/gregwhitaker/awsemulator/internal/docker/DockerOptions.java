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

import io.github.gregwhitaker.awsemulator.model.DockerTransport;
import org.gradle.api.GradleException;
import java.net.URI;

/** Immutable Docker CLI selection; authentication remains with Docker's configured client. */
public record DockerOptions(String host, String context, DockerTransport transport) {
    public static final DockerOptions DEFAULT = new DockerOptions("", "", DockerTransport.DIRECT);

    public DockerOptions {
        if (host == null || context == null || transport == null) throw new GradleException("awsEmulator.docker settings must not be null.");
        if (!host.isEmpty() && !context.isEmpty()) throw new GradleException("Set only one of awsEmulator.docker.host and context.");
        if (host.chars().anyMatch(Character::isISOControl) || context.chars().anyMatch(Character::isISOControl)) {
            throw new GradleException("awsEmulator.docker host and context must not contain control characters.");
        }
        if (!host.isEmpty()) {
            try {
                URI uri = URI.create(host);
                if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
                    throw new IllegalArgumentException("Credentials in URI");
                }
            } catch (IllegalArgumentException e) {
                throw new GradleException("awsEmulator.docker.host must be a valid Docker address without an embedded password.");
            }
        }
    }

    public boolean tunneled() { return transport == DockerTransport.TUNNEL; }
}
