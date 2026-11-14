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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gregwhitaker.awsemulator.aws.internal.FixtureFiles;
import org.gradle.api.GradleException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/** Strict DynamoDB AttributeValue JSON, preserving number precision and binary/set types. */
final class DynamoDbItemFixtures {
    List<Map<String, AttributeValue>> read(Path file) {
        JsonNode root = FixtureFiles.read(file);
        if (!root.isArray()) throw invalid("Fixture must be a JSON array");
        List<Map<String, AttributeValue>> result = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject() || node.isEmpty()) throw invalid("Items must be nonempty objects");
            result.add(attributes(node, 0));
        }
        return List.copyOf(result);
    }

    ObjectNode encode(Map<String, AttributeValue> item) {
        var result = JsonNodeFactory.instance.objectNode();
        new TreeMap<>(item).forEach((name, value) -> result.set(name, encode(value)));
        return result;
    }

    private JsonNode encode(AttributeValue value) {
        var result = JsonNodeFactory.instance.objectNode();
        if (value.s() != null) result.put("S", value.s());
        else if (value.n() != null) result.put("N", value.n());
        else if (value.b() != null) result.put("B", Base64.getEncoder().encodeToString(value.b().asByteArray()));
        else if (value.bool() != null) result.put("BOOL", value.bool());
        else if (Boolean.TRUE.equals(value.nul())) result.put("NULL", true);
        else if (value.hasM()) result.set("M", encode(value.m()));
        else if (value.hasL()) value.l().forEach(entry -> result.withArray("L").add(encode(entry)));
        else if (value.hasSs()) value.ss().stream().sorted().forEach(entry -> result.withArray("SS").add(entry));
        else if (value.hasNs()) value.ns().stream().map(DynamoDbItemFixtures::canonicalNumber).sorted()
            .forEach(entry -> result.withArray("NS").add(entry));
        else if (value.hasBs()) value.bs().stream().map(bytes -> Base64.getEncoder().encodeToString(bytes.asByteArray())).sorted()
            .forEach(entry -> result.withArray("BS").add(entry));
        else throw invalid("Unsupported exported attribute type");
        if (value.hasL() && value.l().isEmpty()) result.putArray("L");
        // Reuse strict type validation, including nesting and numeric limits, on service responses.
        value(result, 0);
        return result;
    }

    private Map<String, AttributeValue> attributes(JsonNode object, int depth) {
        if (!object.isObject()) throw invalid("M values must be objects");
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> {
            if (field.getKey().isEmpty()) throw invalid("Attribute names must not be empty");
            result.put(field.getKey(), value(field.getValue(), depth));
        });
        return result;
    }

    private AttributeValue value(JsonNode node, int depth) {
        if (depth > 32) throw invalid("Attributes exceed the 32-level nesting limit");
        if (!node.isObject() || node.size() != 1) throw invalid("Each attribute must contain exactly one DynamoDB type tag");
        String type = node.fieldNames().next();
        JsonNode data = node.get(type);
        var result = AttributeValue.builder();
        switch (type) {
            case "S" -> result.s(string(data));
            case "N" -> result.n(number(data));
            case "B" -> result.b(binary(data));
            case "BOOL" -> {
                if (!data.isBoolean()) throw invalid("BOOL must contain a boolean");
                result.bool(data.booleanValue());
            }
            case "NULL" -> {
                if (!data.isBoolean() || !data.booleanValue()) throw invalid("NULL must contain true");
                result.nul(true);
            }
            case "M" -> result.m(attributes(data, depth + 1));
            case "L" -> {
                if (!data.isArray()) throw invalid("L must contain an array");
                List<AttributeValue> values = new ArrayList<>();
                data.forEach(item -> values.add(value(item, depth + 1)));
                result.l(values);
            }
            case "SS" -> result.ss(set(data, this::string));
            case "NS" -> result.ns(set(data, item -> canonicalNumber(number(item))));
            case "BS" -> result.bs(set(data, this::binary));
            default -> throw invalid("Unknown DynamoDB type tag");
        }
        return result.build();
    }

    private <T> List<T> set(JsonNode data, java.util.function.Function<JsonNode, T> converter) {
        if (!data.isArray() || data.isEmpty()) throw invalid("Sets must contain a nonempty array");
        List<T> values = new ArrayList<>();
        Set<T> unique = new HashSet<>();
        for (JsonNode item : data) {
            T value = converter.apply(item);
            if (value instanceof String text && text.isEmpty() || value instanceof SdkBytes bytes && bytes.asByteArray().length == 0) {
                throw invalid("Set members must not be empty");
            }
            if (!unique.add(value)) throw invalid("Set members must be unique");
            values.add(value);
        }
        return values;
    }

    private String string(JsonNode node) {
        if (!node.isTextual()) throw invalid("S, N, and B values must be strings");
        return node.textValue();
    }

    private String number(JsonNode node) {
        String text = string(node);
        try {
            BigDecimal value = new BigDecimal(text).stripTrailingZeros();
            long exponent = (long) value.precision() - value.scale() - 1;
            if (value.precision() > 38 || value.signum() != 0 && (exponent < -130 || exponent > 125)) throw new NumberFormatException();
            return text;
        } catch (NumberFormatException e) { throw invalid("N values must be DynamoDB numbers represented as strings"); }
    }

    static String canonicalNumber(String number) { return new BigDecimal(number).stripTrailingZeros().toString(); }

    private SdkBytes binary(JsonNode node) {
        try { return SdkBytes.fromByteArray(Base64.getDecoder().decode(string(node))); }
        catch (IllegalArgumentException e) { throw invalid("B values must be base64 strings"); }
    }

    private GradleException invalid(String message) {
        return new GradleException("Invalid DynamoDB fixture: " + message + " (values omitted).");
    }
}
