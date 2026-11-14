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

package example;

import io.github.gregwhitaker.awsemulator.runtime.EmulatorImage;
import io.github.gregwhitaker.awsemulator.testcontainers.AwsEmulatorContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublishedContainerTest {
    @Test
    void resolvesTransitiveRuntimeWithoutGradleOrDocker() {
        try (var container = new AwsEmulatorContainer()) {
            assertEquals("us-east-1", container.getRegion());
            assertThrows(IllegalStateException.class, container::getEndpoint);
            assertTrue(EmulatorImage.dockerfile(EmulatorImage.DEFAULT_VERSION, "linux/amd64").contains("--bind"));
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.gradle.api.Project"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.testcontainers.junit.jupiter.Testcontainers"));
    }
}
