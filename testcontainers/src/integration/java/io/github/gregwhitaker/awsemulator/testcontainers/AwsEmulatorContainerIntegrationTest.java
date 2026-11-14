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

import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AwsEmulatorContainerIntegrationTest {
    @Test
    void signedRequestsUseIsolatedTlsAndCleanupAllowsFreshRestart() throws Exception {
        SSLContext defaultTls = SSLContext.getDefault();
        String originalTrustStore = System.getProperty("javax.net.ssl.trustStore");
        try (var emulator = new AwsEmulatorContainer()) {
            emulator.start();
            String id = emulator.getContainerId();
            Path files = emulator.getCredentialsFile().getParent();
            try (var sts = StsClient.builder().endpointOverride(emulator.getEndpoint())
                    .region(Region.of(emulator.getRegion())).credentialsProvider(emulator.getCredentialsProvider())
                    .httpClientBuilder(emulator.getHttpClientBuilder())
                    .overrideConfiguration(config -> config.putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                    .build();
                 var s3 = s3(emulator);
                 var sqs = SqsClient.builder().endpointOverride(emulator.getEndpoint())
                     .region(Region.of(emulator.getRegion())).credentialsProvider(emulator.getCredentialsProvider())
                     .httpClientBuilder(emulator.getHttpClientBuilder()).build()) {
                assertNotNull(sts.getCallerIdentity().account());
                s3.createBucket(request -> request.bucket("standalone-state"));
                assertTrue(s3.listBuckets().buckets().stream().anyMatch(bucket -> bucket.name().equals("standalone-state")));
                String queue = sqs.createQueue(request -> request.queueName("standalone-events")).queueUrl();
                sqs.sendMessage(request -> request.queueUrl(queue).messageBody("hello"));
                assertEquals("hello", sqs.receiveMessage(request -> request.queueUrl(queue)).messages().get(0).body());
            }
            assertSame(defaultTls, SSLContext.getDefault());
            assertEquals(originalTrustStore, System.getProperty("javax.net.ssl.trustStore"));
            assertThrows(IllegalStateException.class, () -> emulator.withRegion("eu-west-1"));
            emulator.stop();
            assertFalse(Files.exists(files));
            assertRemoved(id);
            assertThrows(IllegalStateException.class, emulator::getEndpoint);
            emulator.start();
            assertNotEquals(id, emulator.getContainerId());
            try (var s3 = s3(emulator)) { assertTrue(s3.listBuckets().buckets().isEmpty()); }
        }
    }

    @Test
    void simultaneousContainersHaveSeparatePortsCredentialsAndResources() {
        try (var first = new AwsEmulatorContainer()) {
            first.start();
            try (var second = new AwsEmulatorContainer(DockerImageName.parse(first.getDockerImageName()))
                    .waitingFor(Wait.forListeningPort())) {
                second.start();
                // A custom image and wait strategy must still produce a verified connection.
                assertNotEquals(first.getEndpoint(), second.getEndpoint());
                assertNotEquals(first.getCredentialsFile(), second.getCredentialsFile());
                try (var firstS3 = s3(first); var secondS3 = s3(second)) {
                    firstS3.createBucket(request -> request.bucket("first-only"));
                    assertTrue(secondS3.listBuckets().buckets().isEmpty());
                }
            }
        }
    }

    @Test
    void failedStartupRemovesContainerAndExportedCredentials() {
        class FailingContainer extends AwsEmulatorContainer {
            Path files;
            String id;
            @Override
            protected void waitUntilContainerStarted() {
                super.waitUntilContainerStarted();
                files = getCredentialsFile().getParent();
                id = getContainerId();
                throw new IllegalStateException("Intentional startup failure after readiness");
            }
        }
        try (var emulator = new FailingContainer()) {
            var failure = assertThrows(ContainerLaunchException.class, emulator::start);
            if (emulator.files == null) throw failure;
            assertFalse(Files.exists(emulator.files));
            assertRemoved(emulator.id);
            assertThrows(IllegalStateException.class, emulator::getCredentialsProvider);
        }
    }

    private static S3Client s3(AwsEmulatorContainer emulator) {
        return S3Client.builder().endpointOverride(emulator.getEndpoint()).region(Region.of(emulator.getRegion()))
            .credentialsProvider(emulator.getCredentialsProvider()).httpClientBuilder(emulator.getHttpClientBuilder())
            .forcePathStyle(true).build();
    }

    private static void assertRemoved(String id) {
        assertThrows(NotFoundException.class, () -> DockerClientFactory.instance().client().inspectContainerCmd(id).exec());
    }
}
