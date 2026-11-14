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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** Compares creation settings with service descriptions, excluding generated and runtime metadata. */
final class DynamoDbTableDrift {
    private final List<String> mismatches = new ArrayList<>();

    static void validate(CreateTableRequest request, TableDescription table, Function<String, String> keyArn) {
        if (table == null) throw new GradleException("DynamoDB returned no table description for: " + request.tableName());
        var drift = new DynamoDbTableDrift();
        drift.compare("keySchema", schema(request.keySchema()), schema(table.keySchema()));
        drift.compare("attributeDefinitions", attributes(request.attributeDefinitions()), attributes(table.attributeDefinitions()));
        drift.compare("billingMode", "PROVISIONED", table.billingModeSummary() == null
            ? "PROVISIONED" : table.billingModeSummary().billingModeAsString());
        drift.throughput("provisionedThroughput", request.provisionedThroughput(), table.provisionedThroughput());
        drift.globalIndexes(request.globalSecondaryIndexes(), table.globalSecondaryIndexes());
        drift.localIndexes(request.localSecondaryIndexes(), table.localSecondaryIndexes());
        drift.streams(request.streamSpecification(), table.streamSpecification());
        drift.encryption(request, table.sseDescription(), keyArn);
        if (!drift.mismatches.isEmpty()) {
            throw new GradleException("DynamoDB table '" + request.tableName() + "' configuration mismatch:\n - "
                + String.join("\n - ", drift.mismatches)
                + "\nThe existing table and its items were not changed. Align the task definition with the table, "
                + "or explicitly delete and recreate it (or use a fresh managed runtime).");
        }
    }

    private void globalIndexes(List<GlobalSecondaryIndex> requested, List<GlobalSecondaryIndexDescription> existing) {
        var expected = byName(requested, GlobalSecondaryIndex::indexName);
        var actual = byName(existing, GlobalSecondaryIndexDescription::indexName);
        for (String name : names(expected, actual)) {
            String path = "globalSecondaryIndexes[" + name + "]";
            var wanted = expected.get(name);
            var found = actual.get(name);
            if (wanted == null || found == null) {
                compare(path, wanted == null ? "absent" : "present", found == null ? "absent" : "present");
            } else {
                compare(path + ".keySchema", schema(wanted.keySchema()), schema(found.keySchema()));
                projection(path + ".projection", wanted.projection(), found.projection());
                throughput(path + ".provisionedThroughput", wanted.provisionedThroughput(), found.provisionedThroughput());
            }
        }
    }

    private void localIndexes(List<LocalSecondaryIndex> requested, List<LocalSecondaryIndexDescription> existing) {
        var expected = byName(requested, LocalSecondaryIndex::indexName);
        var actual = byName(existing, LocalSecondaryIndexDescription::indexName);
        for (String name : names(expected, actual)) {
            String path = "localSecondaryIndexes[" + name + "]";
            var wanted = expected.get(name);
            var found = actual.get(name);
            if (wanted == null || found == null) {
                compare(path, wanted == null ? "absent" : "present", found == null ? "absent" : "present");
            } else {
                compare(path + ".keySchema", schema(wanted.keySchema()), schema(found.keySchema()));
                projection(path + ".projection", wanted.projection(), found.projection());
            }
        }
    }

    private void projection(String path, Projection expected, Projection actual) {
        compare(path + ".projectionType", expected == null ? null : expected.projectionTypeAsString(),
            actual == null ? null : actual.projectionTypeAsString());
        compare(path + ".nonKeyAttributes", expected == null ? null : new TreeSet<>(expected.nonKeyAttributes()),
            actual == null ? null : new TreeSet<>(actual.nonKeyAttributes()));
    }

    private void throughput(String path, ProvisionedThroughput expected, ProvisionedThroughputDescription actual) {
        compare(path + ".readCapacityUnits", expected == null ? null : expected.readCapacityUnits(),
            actual == null ? null : actual.readCapacityUnits());
        compare(path + ".writeCapacityUnits", expected == null ? null : expected.writeCapacityUnits(),
            actual == null ? null : actual.writeCapacityUnits());
    }

    private void streams(StreamSpecification expected, StreamSpecification actual) {
        boolean enabled = expected != null && Boolean.TRUE.equals(expected.streamEnabled());
        compare("streamSpecification.streamEnabled", enabled, actual != null && Boolean.TRUE.equals(actual.streamEnabled()));
        if (enabled) compare("streamSpecification.streamViewType", expected.streamViewTypeAsString(),
            actual == null ? null : actual.streamViewTypeAsString());
    }

    private void encryption(CreateTableRequest request, SSEDescription actual, Function<String, String> keyArn) {
        var expected = request.sseSpecification();
        boolean enabled = expected != null && Boolean.TRUE.equals(expected.enabled());
        boolean existingEnabled = actual != null && actual.status() != SSEStatus.DISABLED;
        compare("sseSpecification.enabled", enabled, existingEnabled);
        if (!enabled || !existingEnabled) return;
        compare("sseSpecification.sseType", expected.sseTypeAsString() == null ? "KMS" : expected.sseTypeAsString(), actual.sseTypeAsString());
        String key = expected.kmsMasterKeyId() == null ? "alias/aws/dynamodb" : expected.kmsMasterKeyId();
        String resolved;
        try {
            resolved = key.startsWith("arn:") && key.contains(":key/") ? key : keyArn.apply(key);
            if (resolved == null || resolved.isBlank()) throw new GradleException("KMS returned no key ARN.");
        } catch (RuntimeException failure) {
            throw new GradleException("Cannot verify DynamoDB table '" + request.tableName()
                + "' sseSpecification.kmsMasterKeyId: could not resolve '" + key + "' using KMS.", failure);
        }
        if (!Objects.equals(resolved, actual.kmsMasterKeyArn())) {
            compare("sseSpecification.kmsMasterKeyId", key.equals(resolved) ? key : key + " (" + resolved + ")", actual.kmsMasterKeyArn());
        }
    }

    private void compare(String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) mismatches.add(path + ": requested " + value(expected) + ", existing " + value(actual));
    }

    private static String value(Object value) { return value == null ? "<not reported>" : value.toString(); }

    private static Map<String, String> schema(List<KeySchemaElement> schema) {
        var values = new TreeMap<String, String>();
        for (var key : schema) values.put(key.keyTypeAsString(), key.attributeName());
        return values;
    }

    private static Map<String, String> attributes(List<AttributeDefinition> attributes) {
        var values = new TreeMap<String, String>();
        for (var attribute : attributes) values.put(attribute.attributeName(), attribute.attributeTypeAsString());
        return values;
    }

    private static <T> Map<String, T> byName(List<T> indexes, Function<T, String> name) {
        var values = new TreeMap<String, T>();
        for (T index : indexes) values.put(name.apply(index), index);
        return values;
    }

    private static TreeSet<String> names(Map<String, ?> expected, Map<String, ?> actual) {
        var names = new TreeSet<>(expected.keySet());
        names.addAll(actual.keySet());
        return names;
    }
}
