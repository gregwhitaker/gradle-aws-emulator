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

import io.github.gregwhitaker.awsemulator.aws.internal.FixtureFiles;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class S3ObjectFixturesTest {
    @TempDir Path temp;
    private final S3ObjectFixtures fixtures = new S3ObjectFixtures();

    @Test void exportsAllPagesInStableOrderAndReplaysBytesKeysAndMetadata() throws Exception {
        var pages = new AtomicInteger();
        byte[] bytes = {0, 1, -1};
        var source = SdkStub.client(S3Client.class, (proxy, method, args) -> switch (method.getName()) {
            case "listObjectsV2" -> {
                var request = (ListObjectsV2Request) args[0];
                assertEquals("source-bucket", request.bucket());
                assertEquals("assets/", request.prefix());
                if (pages.getAndIncrement() == 0) yield ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("next")
                    .contents(S3Object.builder().key("assets/z.bin").eTag("etag").size(3L).build()).build();
                assertEquals("next", request.continuationToken());
                yield ListObjectsV2Response.builder().contents(S3Object.builder().key("assets/../../a.bin").eTag("etag").build()).build();
            }
            case "getObject" -> {
                assertEquals("etag", ((GetObjectRequest) args[0]).ifMatch());
                yield new ResponseInputStream<>(GetObjectResponse.builder().contentType("application/custom")
                    .metadata(Map.of("fixture", "sensitive-metadata")).build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
            }
            default -> throw new AssertionError(method.getName());
        });
        Path output = temp.resolve("objects.json");
        assertEquals(2, fixtures.export(source, "source-bucket", "assets/", output));
        var objects = fixtures.read(output);
        assertEquals(List.of("assets/../../a.bin", "assets/z.bin"), objects.stream().map(S3ObjectFixtures.ObjectFixture::key).toList());
        var writes = new AtomicInteger();
        var target = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("putObject", method.getName());
            var request = (PutObjectRequest) args[0];
            assertEquals("target-bucket", request.bucket());
            assertEquals(objects.get(writes.getAndIncrement()).key(), request.key());
            assertEquals("application/custom", request.contentType());
            assertEquals(Map.of("fixture", "sensitive-metadata"), request.metadata());
            try (var stream = ((RequestBody) args[1]).contentStreamProvider().newStream()) { assertArrayEquals(bytes, stream.readAllBytes()); }
            return PutObjectResponse.builder().build();
        });
        fixtures.replay(target, "target-bucket", objects);
        assertEquals(2, writes.get());
        try (var files = Files.list(temp)) { assertEquals(List.of(output), files.toList()); }
        byte[] first = Files.readAllBytes(output);
        pages.set(0);
        fixtures.export(source, "source-bucket", "assets/", output);
        assertArrayEquals(first, Files.readAllBytes(output));
    }

    @Test void emptyBucketProducesReplayableEmptyFixture() throws Exception {
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("listObjectsV2", method.getName());
            return ListObjectsV2Response.builder().build();
        });
        Path output = temp.resolve("empty.json");
        assertEquals(0, fixtures.export(client, "empty-bucket", "", output));
        assertTrue(fixtures.read(output).isEmpty());
        fixtures.replay(client, "empty-bucket", fixtures.read(output));
    }

    @Test void failedOrOversizedExportPreservesPreviousArtifact() throws Exception {
        Path output = temp.resolve("objects.json");
        Files.writeString(output, "previous export");
        for (boolean oversized : new boolean[] {false, true}) {
            var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
                if (method.getName().equals("listObjectsV2")) return ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("a").size(oversized ? (long) FixtureFiles.MAX_BYTES + 1 : 1L).build()).build();
                throw S3Exception.builder().statusCode(404).build();
            });
            assertThrows(RuntimeException.class, () -> fixtures.export(client, "bucket", "", output));
            assertEquals("previous export", Files.readString(output));
        }
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }

    @Test void boundsEncodedArtifactSizeAndRejectsMissingOrRepeatedTokens() throws Exception {
        Path output = temp.resolve("objects.json");
        for (String token : Arrays.asList(null, "repeat")) {
            var client = SdkStub.client(S3Client.class, (proxy, method, args) ->
                ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken(token).build());
            assertThrows(GradleException.class, () -> fixtures.export(client, "bucket", "", output));
            assertFalse(Files.exists(output));
        }
        byte[] bytes = new byte[13 * 1024 * 1024];
        var large = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            if (method.getName().equals("listObjectsV2")) return ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("a").size((long) bytes.length).build()).build();
            return new ResponseInputStream<>(GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        });
        assertThrows(GradleException.class, () -> fixtures.export(large, "bucket", "", output));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsInvalidFilesWithoutLeakingPayloads() throws Exception {
        String object = "{\"key\":\"a\",\"body\":\"AAH/\",\"metadata\":{}}";
        for (String content : List.of(
                "{\"schemaVersion\":2,\"objects\":[]}",
                "{\"schemaVersion\":1,\"objects\":[" + object + "," + object + "]}",
                "{\"schemaVersion\":1,\"objects\":[{\"key\":\"a\",\"body\":\"sensitive-value\",\"metadata\":{}}]}",
                "{\"schemaVersion\":1,\"objects\":[{\"key\":\"a\",\"body\":\"\",\"metadata\":{\"a\":42}}]}",
                "{\"schemaVersion\":1,\"objects\":[{\"key\":\"a\",\"body\":\"\",\"metadata\":{},\"unknown\":\"sensitive-value\"}]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"objects\":[]} sensitive-value",
                "{\"schemaVersion\":1,\"objects\":[]} {}")) {
            Path file = temp.resolve("invalid.json");
            Files.writeString(file, content);
            var error = assertThrows(GradleException.class, () -> fixtures.read(file), content);
            assertFalse(error.toString().contains("sensitive-value"));
            assertNull(error.getCause());
        }
    }
}
