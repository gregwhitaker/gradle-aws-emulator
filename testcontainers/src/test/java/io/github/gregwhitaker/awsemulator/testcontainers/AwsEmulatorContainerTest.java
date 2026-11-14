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

package io.github.gregwhitaker.awsemulator.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AwsEmulatorContainerTest {
    @Test
    void constructionAndValidationDoNotRequireDocker() {
        try (var container = new AwsEmulatorContainer()) {
            assertEquals("us-east-1", container.getRegion());
            assertSame(container, container.withRegion("eu-west-1"));
            assertThrows(IllegalStateException.class, container::getEndpoint);
            assertThrows(IllegalStateException.class, container::getCredentialsProvider);
            assertThrows(IllegalStateException.class, container::getCertificateFile);
            assertThrows(IllegalStateException.class, container::getHttpClientBuilder);
            assertThrows(IllegalArgumentException.class, () -> container.withRegion("invalid region"));
            assertThrows(IllegalArgumentException.class, () -> container.withReuse(true));
            assertThrows(IllegalArgumentException.class, () -> container.withStartupTimeout(Duration.ZERO));
        }
        assertThrows(IllegalArgumentException.class, () -> new AwsEmulatorContainer("latest"));
        try (var custom = new AwsEmulatorContainer(DockerImageName.parse("example/emulator:1"))) {
            assertEquals("us-east-1", custom.getRegion());
        }
    }

    @Test
    void suppliesAuthenticationAndExplicitTelemetryWithoutJUnitOrGradleRuntime() {
        class ConfigurableContainer extends AwsEmulatorContainer {
            void configureNow() { configure(); }
        }
        try (var container = new ConfigurableContainer()) {
            container.configureNow();
            assertArrayEquals(new String[] {"--port", "9297", "--auth", "on", "--telemetry", "off"}, container.getCommandParts());
            container.withTelemetryEnabled(true);
            container.configureNow();
            assertEquals("on", container.getCommandParts()[5]);
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.gradle.api.Project"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.testcontainers.junit.jupiter.Testcontainers"));
    }

    @Test
    void createsMappedEndpointsIncludingIpv6() {
        assertEquals("https://localhost:12345", AwsEmulatorContainer.endpoint("localhost", 12345).toString());
        assertEquals("https://[::1]:12345", AwsEmulatorContainer.endpoint("::1", 12345).toString());
    }

    @Test
    void readinessFailsPromptlyWhenTheEmulatorHasExited() {
        class ExitedContainer extends AwsEmulatorContainer {
            @Override public String getHost() { return "localhost"; }
            @Override public Integer getMappedPort(int port) { return 12345; }
            @Override public boolean isRunning() { return false; }
            void awaitReadiness() { waitUntilContainerStarted(); }
        }
        try (var container = new ExitedContainer()) {
            container.withStartupTimeout(Duration.ofMillis(1));
            var failure = assertThrows(ContainerLaunchException.class, container::awaitReadiness);
            assertTrue(failure.getMessage().contains("exited before becoming ready"));
        }
    }
}
