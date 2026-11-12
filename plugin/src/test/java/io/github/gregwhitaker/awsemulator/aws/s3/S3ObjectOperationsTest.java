package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class S3ObjectOperationsTest {
    @TempDir Path temp;
    private final S3ObjectOperations operations = new S3ObjectOperations();

    @Test void uploadsNestedPathsWithDeterministicContentTypesMetadataAndBinaryBodies() throws Exception {
        Files.createDirectories(temp.resolve("nested"));
        Files.writeString(temp.resolve("nested/data.json"), "{\"ok\":true}");
        Files.write(temp.resolve("image.custom"), new byte[] {0, 1, -1});
        var uploads = operations.directory(temp, "fixtures", Map.of("custom", "image/custom"));
        assertEquals(List.of("fixtures/image.custom", "fixtures/nested/data.json"), uploads.stream().map(S3ObjectOperations.Upload::key).toList());
        assertEquals(List.of("image/custom", "application/json"), uploads.stream().map(S3ObjectOperations.Upload::contentType).toList());
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            assertEquals("putObject", method.getName());
            var request = (PutObjectRequest) args[0];
            assertEquals("fixture-bucket", request.bucket());
            assertEquals("image/custom", request.contentType());
            assertEquals(Map.of("scenario", "demo"), request.metadata());
            try (var body = ((RequestBody) args[1]).contentStreamProvider().newStream()) {
                assertArrayEquals(new byte[] {0, 1, -1}, body.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        operations.upload(client, "fixture-bucket", uploads.get(0), Map.of("scenario", "demo"));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void refusesSymlinksBeforeUploading() throws Exception {
        Files.writeString(temp.resolve("data.txt"), "example");
        Files.createSymbolicLink(temp.resolve("link"), temp.resolve("data.txt"));
        assertThrows(GradleException.class, () -> operations.directory(temp, "", Map.of()));
    }

    @Test void measuresKeyLimitsInUtf8Bytes() {
        assertThrows(GradleException.class, () -> S3ObjectOperations.key("é".repeat(513)));
        assertEquals("é".repeat(512), S3ObjectOperations.key("é".repeat(512)));
    }

    @Test void listsEveryPageAndRejectsBrokenContinuationTokens() {
        var count = new AtomicInteger();
        var client = SdkStub.client(S3Client.class, (proxy, method, args) -> {
            var request = (ListObjectsV2Request) args[0];
            assertEquals("fixtures/", request.prefix());
            if (count.getAndIncrement() == 0) return ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("next")
                .contents(S3Object.builder().key("fixtures/a").build()).build();
            assertEquals("next", request.continuationToken());
            return ListObjectsV2Response.builder().isTruncated(false).contents(S3Object.builder().key("fixtures/b").build()).build();
        });
        assertEquals(2, operations.list(client, "fixture-bucket", "fixtures/").size());
        var broken = SdkStub.client(S3Client.class, (proxy, method, args) -> ListObjectsV2Response.builder()
            .isTruncated(true).nextContinuationToken("again").build());
        assertThrows(GradleException.class, () -> operations.list(broken, "fixture-bucket", ""));
    }

    @Test void downloadsBinaryDataAndPreservesTheDestinationWhenAReadFails() throws Exception {
        Path output = temp.resolve("object.bin");
        byte[] bytes = {0, 1, -1};
        operations.download(download(new ByteArrayInputStream(bytes)), "fixture-bucket", "object", output);
        assertArrayEquals(bytes, Files.readAllBytes(output));
        assertThrows(GradleException.class, () -> operations.download(download(new InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("read failed"); }
        }), "fixture-bucket", "object", output));
        assertArrayEquals(bytes, Files.readAllBytes(output));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }

    private S3Client download(InputStream stream) {
        return SdkStub.client(S3Client.class, (proxy, method, args) ->
            new ResponseInputStream<>(GetObjectResponse.builder().build(), AbortableInputStream.create(stream)));
    }
}
