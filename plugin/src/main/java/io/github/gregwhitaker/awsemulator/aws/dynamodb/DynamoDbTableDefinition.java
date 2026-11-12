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

package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks the table's schema and required options before starting the emulator. */
final class DynamoDbTableDefinition {
    private DynamoDbTableDefinition() {}

    static void validateName(String name) {
        if (!name.matches("[A-Za-z0-9_.-]{3,255}")) {
            throw new GradleException("tableName must contain 3–255 letters, numbers, underscores, hyphens, or periods.");
        }
    }

    static void validate(CreateTableRequest request) {
        validateName(request.tableName());
        Map<String, ScalarAttributeType> attributes = new HashMap<>();
        for (var attribute : request.attributeDefinitions()) {
            attributeName(attribute.attributeName());
            if (attribute.attributeType() == null || attribute.attributeType() == ScalarAttributeType.UNKNOWN_TO_SDK_VERSION) {
                throw new GradleException("attributeDefinitions must use scalar types S, N, or B.");
            }
            if (attributes.putIfAbsent(attribute.attributeName(), attribute.attributeType()) != null) {
                throw new GradleException("Duplicate attribute definition: " + attribute.attributeName());
            }
        }
        Set<String> used = new HashSet<>();
        Map<KeyType, String> primary = keySchema(request.keySchema(), attributes, used, "table");
        throughput(request.provisionedThroughput(), "table");
        Set<String> indexes = new HashSet<>();
        for (var index : request.globalSecondaryIndexes()) {
            indexName(index.indexName(), indexes);
            keySchema(index.keySchema(), attributes, used, index.indexName());
            projection(index.projection(), index.indexName());
            throughput(index.provisionedThroughput(), index.indexName());
        }
        for (var index : request.localSecondaryIndexes()) {
            indexName(index.indexName(), indexes);
            Map<KeyType, String> keys = keySchema(index.keySchema(), attributes, used, index.indexName());
            if (!primary.containsKey(KeyType.RANGE) || !keys.containsKey(KeyType.RANGE)
                    || !primary.get(KeyType.HASH).equals(keys.get(KeyType.HASH))) {
                throw new GradleException("Local secondary indexes require a composite table key and must share its HASH key: " + index.indexName());
            }
            projection(index.projection(), index.indexName());
        }
        if (!used.equals(attributes.keySet())) {
            throw new GradleException("attributeDefinitions must describe exactly the attributes used by the table and index key schemas.");
        }
        var stream = request.streamSpecification();
        if (stream != null && (stream.streamEnabled() == null || Boolean.TRUE.equals(stream.streamEnabled())
                && (stream.streamViewType() == null || stream.streamViewType() == StreamViewType.UNKNOWN_TO_SDK_VERSION))) {
            throw new GradleException("streamSpecification requires streamEnabled and, when enabled, a valid streamViewType.");
        }
    }

    private static Map<KeyType, String> keySchema(List<KeySchemaElement> schema, Map<String, ScalarAttributeType> attributes,
                                                 Set<String> used, String owner) {
        Map<KeyType, String> keys = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (var key : schema) {
            attributeName(key.attributeName());
            if (key.keyType() == null || key.keyType() == KeyType.UNKNOWN_TO_SDK_VERSION
                    || keys.putIfAbsent(key.keyType(), key.attributeName()) != null || !names.add(key.attributeName())) {
                throw new GradleException("keySchema requires one HASH key and at most one distinct RANGE key: " + owner);
            }
            if (!attributes.containsKey(key.attributeName())) {
                throw new GradleException("Missing attribute definition for key '" + key.attributeName() + "' in " + owner + ".");
            }
            used.add(key.attributeName());
        }
        if (!keys.containsKey(KeyType.HASH)) {
            throw new GradleException("keySchema requires one HASH key and at most one distinct RANGE key: " + owner);
        }
        return keys;
    }

    private static void attributeName(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 255) {
            throw new GradleException("Key attribute names must contain 1–255 characters.");
        }
    }

    private static void indexName(String name, Set<String> names) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{3,255}") || !names.add(name)) {
            throw new GradleException("Secondary index names must be unique and contain 3–255 letters, numbers, underscores, hyphens, or periods.");
        }
    }

    private static void projection(Projection projection, String name) {
        if (projection == null || projection.projectionType() == null || projection.projectionType() == ProjectionType.UNKNOWN_TO_SDK_VERSION) {
            throw new GradleException("Secondary index '" + name + "' requires an ALL, KEYS_ONLY, or INCLUDE projection.");
        }
        if (projection.projectionType() == ProjectionType.INCLUDE) {
            if (projection.nonKeyAttributes().isEmpty()) throw new GradleException("INCLUDE projection requires nonKeyAttributes: " + name);
        } else if (!projection.nonKeyAttributes().isEmpty()) {
            throw new GradleException("Only INCLUDE projections may specify nonKeyAttributes: " + name);
        }
    }

    private static void throughput(ProvisionedThroughput throughput, String owner) {
        if (throughput == null || throughput.readCapacityUnits() == null || throughput.writeCapacityUnits() == null
                || throughput.readCapacityUnits() < 1 || throughput.writeCapacityUnits() < 1) {
            throw new GradleException("provisionedThroughput requires positive readCapacityUnits and writeCapacityUnits: " + owner);
        }
    }
}
