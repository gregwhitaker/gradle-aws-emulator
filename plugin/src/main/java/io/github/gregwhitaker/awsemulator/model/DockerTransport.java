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

package io.github.gregwhitaker.awsemulator.model;

/** How Gradle reaches an owned emulator and obtains its connection files. */
public enum DockerTransport {
    /** Local Docker port publication and a shared host directory. Supports both lifecycle modes. */
    DIRECT,
    /**
     * Build-scoped loopback forwarding through Docker exec, with files copied through Docker.
     * Supports managed runtimes on remote daemons and in containerized runners.
     */
    TUNNEL
}
