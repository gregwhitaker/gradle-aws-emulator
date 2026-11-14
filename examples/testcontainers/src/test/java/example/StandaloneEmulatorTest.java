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

import io.github.gregwhitaker.awsemulator.testcontainers.AwsEmulatorContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class StandaloneEmulatorTest {
    @Container
    static final AwsEmulatorContainer aws = new AwsEmulatorContainer();

    @Test
    void storesAnObjectOverVerifiedHttps() {
        try (var s3 = S3Client.builder()
                .endpointOverride(aws.getEndpoint())
                .region(Region.of(aws.getRegion()))
                .credentialsProvider(aws.getCredentialsProvider())
                .httpClientBuilder(aws.getHttpClientBuilder())
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(request -> request.bucket("testcontainers-example"));
            s3.putObject(request -> request.bucket("testcontainers-example").key("hello.txt"), RequestBody.fromString("hello"));
            assertEquals("hello", s3.getObjectAsBytes(request -> request.bucket("testcontainers-example").key("hello.txt")).asUtf8String());
        }
    }
}
