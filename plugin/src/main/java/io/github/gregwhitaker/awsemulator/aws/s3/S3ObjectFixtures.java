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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gregwhitaker.awsemulator.aws.internal.FixtureFiles;
import org.gradle.api.GradleException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Versioned, portable S3 fixtures. Object keys are JSON data, never local filesystem paths. */
final class S3ObjectFixtures {
    record ObjectFixture(String key, byte[] body, String contentType, Map<String, String> metadata) {}

    int export(S3Client client, String bucket, String prefix, Path output) {
        var entries = new TreeMap<String, ObjectNode>();
        var budget = new FixtureFiles.Budget();
        Set<String> tokens = new HashSet<>();
        String token = null;
        do {
            var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                .maxKeys(1000).continuationToken(token).build());
            for (var object : page.contents()) {
                S3ObjectOperations.key(object.key());
                if (entries.containsKey(object.key())) throw invalid("S3 listing returned duplicate keys; stop concurrent writers");
                if (object.size() != null) FixtureFiles.checkSize(object.size());
                try (var stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(object.key()).ifMatch(object.eTag()).build())) {
                    byte[] body = stream.readNBytes(FixtureFiles.MAX_BYTES + 1);
                    FixtureFiles.checkSize(body.length);
                    var entry = JsonNodeFactory.instance.objectNode().put("key", object.key())
                        .put("body", Base64.getEncoder().encodeToString(body));
                    String contentType = stream.response().contentType();
                    if (contentType != null) {
                        S3ObjectOperations.validateContentType(contentType);
                        entry.put("contentType", contentType);
                    }
                    var metadata = entry.putObject("metadata");
                    new TreeMap<>(stream.response().metadata()).forEach(metadata::put);
                    budget.add(entry);
                    entries.put(object.key(), entry);
                } catch (IOException e) { throw new GradleException("Cannot read S3 object fixture (values omitted)."); }
            }
            if (!Boolean.TRUE.equals(page.isTruncated())) break;
            token = page.nextContinuationToken();
            if (token == null || token.isEmpty() || !tokens.add(token)) throw invalid("S3 listing returned a missing or repeated continuation token");
        } while (true);
        var root = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1);
        var objects = root.putArray("objects");
        entries.values().forEach(objects::add);
        // Validate the exported shape with the same rules used by replay.
        parse(root);
        FixtureFiles.write(output, root);
        return entries.size();
    }

    List<ObjectFixture> read(Path input) { return parse(FixtureFiles.read(input)); }

    private List<ObjectFixture> parse(JsonNode root) {
        fields(root, Set.of("schemaVersion", "objects"));
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                || root.path("schemaVersion").intValue() != 1) throw invalid("Unsupported schemaVersion; expected 1");
        if (!root.path("objects").isArray()) throw invalid("objects must be an array");
        List<ObjectFixture> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (var entry : root.path("objects")) {
            fields(entry, Set.of("key", "body", "contentType", "metadata"));
            String key = S3ObjectOperations.key(string(entry.path("key")));
            if (!keys.add(key)) throw invalid("Duplicate object keys");
            byte[] body;
            try { body = Base64.getDecoder().decode(string(entry.path("body"))); }
            catch (IllegalArgumentException e) { throw invalid("body must be base64"); }
            String type = entry.has("contentType") ? string(entry.get("contentType")) : null;
            if (type != null) S3ObjectOperations.validateContentType(type);
            if (!entry.path("metadata").isObject()) throw invalid("metadata must be an object of string values");
            Map<String, String> metadata = new TreeMap<>();
            entry.path("metadata").fields().forEachRemaining(field -> {
                String value = string(field.getValue());
                if (field.getKey().isBlank() || field.getKey().contains("\r") || field.getKey().contains("\n")
                        || value.contains("\r") || value.contains("\n")) throw invalid("Invalid metadata header");
                metadata.put(field.getKey(), value);
            });
            result.add(new ObjectFixture(key, body, type, Map.copyOf(metadata)));
        }
        return List.copyOf(result);
    }

    void replay(S3Client client, String bucket, List<ObjectFixture> objects) {
        for (var object : objects) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(object.key()).contentType(object.contentType())
                .metadata(object.metadata()).build(), RequestBody.fromBytes(object.body()));
        }
    }

    private void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid("Expected a JSON object");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid("Unsupported field"); });
    }

    private String string(JsonNode node) {
        if (!node.isTextual()) throw invalid("Expected a string");
        return node.textValue();
    }

    private GradleException invalid(String message) { return new GradleException("Invalid S3 fixture: " + message + " (values omitted)."); }
}
