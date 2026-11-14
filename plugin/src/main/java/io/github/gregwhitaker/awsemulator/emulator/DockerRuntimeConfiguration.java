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

package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.model.DockerTransport;
import org.gradle.api.provider.Property;

/** Docker selection and connectivity, inherited by named runtimes and supplied by shared owners. */
public abstract class DockerRuntimeConfiguration {
    /** Docker daemon address passed as --host. Mutually exclusive with context; empty uses Docker's selection. */
    public abstract Property<String> getHost();
    /** Docker context passed as --context. Mutually exclusive with host; empty uses Docker's selection. */
    public abstract Property<String> getContext();
    /**
     * DIRECT uses local port publication and bind mounts. TUNNEL supports managed instances without
     * shared host paths or published emulator ports. Defaults to DIRECT.
     */
    public abstract Property<DockerTransport> getTransport();
}
