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

package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class S3BucketProvisionerTest {
    @Test
    void reusesExistingBucketWithoutCallingCreate() {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("headBucket", method.getName());
            return HeadBucketResponse.builder().bucketRegion("us-east-1").build();
        });
        new S3BucketProvisioner().provision(client, "test-bucket", "us-east-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-1"})
    void onlyAddsALocationConstraintOutsideUsEast1(String region) {
        AtomicInteger creates = new AtomicInteger();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            if (method.getName().equals("headBucket")) throw S3Exception.builder().statusCode(404).build();
            assertEquals("createBucket", method.getName());
            var request = (CreateBucketRequest) args[0];
            assertEquals("test-bucket", request.bucket());
            if (region.equals("us-east-1")) assertNull(request.createBucketConfiguration());
            else assertEquals(region, request.createBucketConfiguration().locationConstraintAsString());
            creates.incrementAndGet();
            return CreateBucketResponse.builder().build();
        });
        new S3BucketProvisioner().provision(client, "test-bucket", region);
        assertEquals(1, creates.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void doesNotTreatErrorsAsMissingBuckets(int status) {
        var failure = S3Exception.builder().statusCode(status).build();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("headBucket", method.getName());
            throw failure;
        });
        assertSame(failure, assertThrows(S3Exception.class, () -> new S3BucketProvisioner().provision(client, "test-bucket", "us-east-1")));
    }

    @Test
    void rejectsAnExistingBucketInAnotherRegion() {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> HeadBucketResponse.builder().bucketRegion("eu-west-1").build());
        assertThrows(GradleException.class, () -> new S3BucketProvisioner().provision(client, "test-bucket", "us-east-1"));
    }

    @Test
    void acceptsAConcurrentCreateOnlyWhenTheBucketIsOwnedAndAccessible() {
        AtomicInteger heads = new AtomicInteger();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            if (method.getName().equals("headBucket")) {
                if (heads.incrementAndGet() == 1) throw S3Exception.builder().statusCode(404).build();
                return HeadBucketResponse.builder().bucketRegion("us-east-1").build();
            }
            assertEquals("createBucket", method.getName());
            throw BucketAlreadyOwnedByYouException.builder().build();
        });
        new S3BucketProvisioner().provision(client, "test-bucket", "us-east-1");
        assertEquals(2, heads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "InvalidBucket", "a..b", "192.168.0.1", "bucket--x-s3", "arn:aws:s3:::bucket"})
    void rejectsInvalidOrUnsupportedBucketNames(String name) {
        assertThrows(GradleException.class, () -> S3BucketProvisioner.validate(name));
    }
}
