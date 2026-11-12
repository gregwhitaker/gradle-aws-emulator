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
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class S3BucketOperationsTest {
    private final S3BucketOperations operations = new S3BucketOperations();

    @Test
    void listsEveryBucketPage() {
        var calls = new AtomicInteger();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("listBuckets", method.getName());
            var request = (ListBucketsRequest) args[0];
            assertEquals(1000, request.maxBuckets());
            if (calls.getAndIncrement() == 0) {
                assertNull(request.continuationToken());
                return ListBucketsResponse.builder().buckets(Bucket.builder().name("first").build()).continuationToken("next").build();
            }
            assertEquals("next", request.continuationToken());
            return ListBucketsResponse.builder().buckets(Bucket.builder().name("last").build()).build();
        });
        assertEquals(List.of("first", "last"), operations.list(client).stream().map(Bucket::name).toList());
        assertEquals(2, calls.get());
    }

    @Test
    void purgesMoreThanOnePageWithoutSkippingObjectsAfterDeletion() {
        var remaining = new ArrayList<>(IntStream.range(0, 1005).mapToObj(i -> "key-" + i).toList());
        var batches = new ArrayList<Integer>();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBucketVersioning" -> GetBucketVersioningResponse.builder().build();
            case "listObjectsV2" -> {
                var request = (ListObjectsV2Request) args[0];
                assertNull(request.continuationToken());
                assertEquals(1000, request.maxKeys());
                yield ListObjectsV2Response.builder().isTruncated(remaining.size() > 1000)
                    .contents(remaining.stream().limit(1000).map(key -> S3Object.builder().key(key).build()).toList()).build();
            }
            case "deleteObjects" -> {
                var request = (DeleteObjectsRequest) args[0];
                batches.add(request.delete().objects().size());
                request.delete().objects().forEach(object -> {
                    assertNull(object.versionId());
                    assertTrue(remaining.remove(object.key()));
                });
                yield DeleteObjectsResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        operations.purge(client, "fixture-bucket");
        assertTrue(remaining.isEmpty());
        assertEquals(List.of(1000, 5), batches);
    }

    @ParameterizedTest
    @EnumSource(value = BucketVersioningStatus.class, names = {"ENABLED", "SUSPENDED"})
    void removesHistoricalVersionsNullVersionsAndDeleteMarkers(BucketVersioningStatus status) {
        var deleted = new ArrayList<ObjectIdentifier>();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBucketVersioning" -> GetBucketVersioningResponse.builder().status(status).build();
            case "listObjectVersions" -> deleted.isEmpty() ? ListObjectVersionsResponse.builder()
                .versions(ObjectVersion.builder().key("data").versionId("v1").build(),
                    ObjectVersion.builder().key("data").versionId("null").build())
                .deleteMarkers(DeleteMarkerEntry.builder().key("data").versionId("marker").build()).build()
                : ListObjectVersionsResponse.builder().build();
            case "deleteObjects" -> {
                deleted.addAll(((DeleteObjectsRequest) args[0]).delete().objects());
                yield DeleteObjectsResponse.builder().build();
            }
            default -> throw new AssertionError(method.getName());
        });
        operations.purge(client, "fixture-bucket");
        assertEquals(List.of("v1", "null", "marker"), deleted.stream().map(ObjectIdentifier::versionId).toList());
    }

    @Test
    void reportsPerObjectErrorsEvenWhenTheDeleteRequestSucceeded() {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBucketVersioning" -> GetBucketVersioningResponse.builder().build();
            case "listObjectsV2" -> ListObjectsV2Response.builder().contents(S3Object.builder().key("locked").build()).build();
            case "deleteObjects" -> DeleteObjectsResponse.builder().errors(S3Error.builder().key("locked").code("AccessDenied").build()).build();
            default -> throw new AssertionError(method.getName());
        });
        var failure = assertThrows(GradleException.class, () -> operations.delete(client, "fixture-bucket", true));
        assertTrue(failure.getMessage().contains("AccessDenied for key 'locked'"));
    }

    @Test
    void stopsIfSuccessfulDeletesDoNotChangeTheRemainingPage() {
        var deletes = new AtomicInteger();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBucketVersioning" -> GetBucketVersioningResponse.builder().build();
            case "listObjectsV2" -> ListObjectsV2Response.builder().contents(S3Object.builder().key("unchanged").build()).build();
            case "deleteObjects" -> { deletes.incrementAndGet(); yield DeleteObjectsResponse.builder().build(); }
            default -> throw new AssertionError(method.getName());
        });
        assertTrue(assertThrows(GradleException.class, () -> operations.purge(client, "fixture-bucket")).getMessage().contains("no progress"));
        assertEquals(1, deletes.get());
    }

    @Test
    void refusesNonemptyBucketsWithoutForceAndPreservesServiceFailures() {
        var nonempty = S3Exception.builder().statusCode(409)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("BucketNotEmpty").build()).build();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("deleteBucket", method.getName());
            throw nonempty;
        });
        var failure = assertThrows(GradleException.class, () -> operations.delete(client, "fixture-bucket", false));
        assertTrue(failure.getMessage().contains("force"));
        assertSame(nonempty, failure.getCause());
        var denied = S3Exception.builder().statusCode(403).build();
        var forbidden = SdkStub.client(S3Client.class, (proxy, method, args) -> { throw denied; });
        assertSame(denied, assertThrows(S3Exception.class, () -> operations.delete(forbidden, "fixture-bucket", true)));
    }

    @Test
    void deletingAnAbsentBucketIsIdempotent() {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> { throw S3Exception.builder().statusCode(404).build(); });
        assertDoesNotThrow(() -> operations.delete(client, "fixture-bucket", false));
        assertDoesNotThrow(() -> operations.delete(client, "fixture-bucket", true));
    }
}
