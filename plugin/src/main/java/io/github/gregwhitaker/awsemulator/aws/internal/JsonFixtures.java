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

package io.github.gregwhitaker.awsemulator.aws.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.api.GradleException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict JSON fixtures. Parse errors deliberately exclude input values and parser excerpts. */
public final class JsonFixtures {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private JsonFixtures() {}

    /** Reads objects with application-defined field names, such as DynamoDB items. */
    public static List<JsonNode> readObjects(Path path) { return read(path, null); }

    public static List<JsonNode> read(Path path, Set<String> fields) {
        try {
            if (Files.size(path) > 16 * 1024 * 1024) throw new GradleException("Fixture exceeds the 16 MiB limit.");
            JsonNode root = JSON.readTree(path.toFile());
            if (root == null || !root.isArray() || root.isEmpty()) throw new GradleException("Fixture must be a nonempty JSON array.");
            List<JsonNode> entries = new ArrayList<>();
            for (JsonNode entry : root) {
                if (!entry.isObject()) throw new GradleException("Each fixture entry must be a JSON object.");
                var keys = entry.fieldNames();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (fields != null && !fields.contains(key)) throw new GradleException("Fixture entry contains an unsupported field.");
                }
                entries.add(entry);
            }
            return entries;
        } catch (GradleException e) { throw e; }
        catch (Exception e) { throw new GradleException("Cannot read JSON fixture; check file access and JSON syntax (values omitted)."); }
    }

    public static String string(JsonNode entry, String field, String fallback) {
        JsonNode value = entry.get(field);
        if (value == null) {
            if (fallback == null) throw new GradleException("Fixture entry requires '" + field + "'.");
            return fallback;
        }
        if (!value.isTextual()) throw new GradleException("Fixture field '" + field + "' must be a string.");
        return value.textValue();
    }

    public static String optional(JsonNode entry, String field) {
        return entry.has(field) ? string(entry, field, null) : null;
    }

    public static String object(String json, String label) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return node.toString();
        } catch (Exception e) { throw new GradleException(label + " must be a JSON object (values omitted)."); }
    }
}
