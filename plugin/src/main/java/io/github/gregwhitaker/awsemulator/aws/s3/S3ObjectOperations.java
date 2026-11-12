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

import org.gradle.api.GradleException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Object fixtures and paginated inspection, without loading object bodies into memory. */
final class S3ObjectOperations {
    record Upload(Path file, String key, String contentType) {}

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
        Map.entry("json", "application/json"), Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"),
        Map.entry("html", "text/html"), Map.entry("css", "text/css"), Map.entry("js", "text/javascript"),
        Map.entry("xml", "application/xml"), Map.entry("pdf", "application/pdf"), Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"));

    static String key(String key) {
        if (key == null || key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new GradleException("S3 objectKey must contain 1–1024 UTF-8 bytes.");
        }
        return key;
    }

    static String contentType(Path file, Map<String, String> overrides) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = overrides.getOrDefault(extension, CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        validateContentType(type);
        return type;
    }

    static void validateContentType(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
            throw new GradleException("S3 contentType must be a nonblank HTTP header value.");
        }
    }

    List<Upload> directory(Path directory, String prefix, Map<String, String> contentTypes) {
        contentTypes.forEach((extension, type) -> {
            if (!extension.matches("[a-z0-9]+")) throw new GradleException("contentTypes keys must be lowercase file extensions without a dot.");
            validateContentType(type);
        });
        String base = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        try (var files = Files.walk(directory)) {
            if (Files.isSymbolicLink(directory)) throw new GradleException("S3 fixture directories must not contain symbolic links.");
            List<Upload> uploads = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new GradleException("S3 fixture directories must not contain symbolic links.");
                if (Files.isDirectory(file)) continue;
                if (!Files.isRegularFile(file)) throw new GradleException("S3 fixtures must be regular files.");
                String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                uploads.add(new Upload(file, key(base + relative), contentType(file, contentTypes)));
            }
            return uploads;
        } catch (java.io.IOException e) {
            throw new GradleException("Cannot read S3 fixture directory.", e);
        }
    }

    void upload(S3Client client, String bucket, Upload upload, Map<String, String> metadata) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(upload.key()).contentType(upload.contentType())
            .metadata(metadata).build(), RequestBody.fromFile(upload.file()));
    }

    List<S3Object> list(S3Client client, String bucket, String prefix) {
        List<S3Object> objects = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        do {
            var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                .maxKeys(1000).continuationToken(token).build());
            objects.addAll(page.contents());
            if (!Boolean.TRUE.equals(page.isTruncated())) break;
            token = page.nextContinuationToken();
            if (token == null || token.isEmpty() || !tokens.add(token)) {
                throw new GradleException("S3 object listing returned a missing or repeated continuation token.");
            }
        } while (true);
        return objects;
    }

    void download(S3Client client, String bucket, String key, Path output) {
        Path temp = null;
        try {
            Path target = output.toAbsolutePath();
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".s3-download-", ".tmp");
            try (var body = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (java.io.IOException e) {
            throw new GradleException("Cannot write S3 object output file.", e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (java.io.IOException ignored) { }
        }
    }
}
