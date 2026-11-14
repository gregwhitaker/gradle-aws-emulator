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

package io.github.gregwhitaker.awsemulator.internal.report;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Collects partial service results, preserving successful reads when another request fails. */
public final class ReportSection {
    private final String service;
    private final Map<ResourceKey, Map<String, Object>> resources = new LinkedHashMap<>();
    private final List<Issue> issues = new ArrayList<>();

    public ReportSection(String service) { this.service = service; }

    /** Adds a resource immediately, allowing subsequent metadata reads to fail independently. */
    public Map<String, Object> resource(String type, String id) {
        return resources.computeIfAbsent(new ResourceKey(type, id), ignored -> new TreeMap<>());
    }

    /** Captures failures without retaining exception messages, which may echo sensitive response data. */
    public void read(String operation, String resource, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            String kind = "ERROR";
            String message = error instanceof RepeatedTokenException
                ? "Repeated pagination token; collection stopped." : error.getClass().getSimpleName();
            if (error instanceof AwsServiceException aws) {
                String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
                if (aws.statusCode() == 501 || Set.of("NotImplemented", "NotImplementedException",
                        "UnsupportedOperation", "UnsupportedOperationException", "UnknownOperationException").contains(code == null ? "" : code)) {
                    kind = "UNSUPPORTED";
                }
                message += " (HTTP " + aws.statusCode() + (code == null ? "" : ", " + code) + ")";
            }
            issues.add(new Issue(operation, resource, kind, message));
        }
    }

    public Result result() {
        var sorted = resources.entrySet().stream()
            .map(entry -> new Resource(entry.getKey().type(), entry.getKey().id(), entry.getValue()))
            .sorted(Comparator.comparing(Resource::type).thenComparing(Resource::id)).toList();
        return new Result(service, issues.isEmpty() ? "COMPLETE" : resources.isEmpty() ? "FAILED" : "PARTIAL",
            sorted, List.copyOf(issues));
    }

    /** Follows all response pages and detects services that repeat a continuation token. */
    public static void pages(Function<String, String> page) {
        var seen = new HashSet<String>();
        String token = null;
        do {
            token = page.apply(token);
            if (token != null && !token.isEmpty() && !seen.add(token)) throw new RepeatedTokenException();
        } while (token != null && !token.isEmpty());
    }

    /** Creates an ordered JSON object, omitting absent fields. Values must be JSON-compatible. */
    public static Map<String, Object> fields(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    /** Selects known configuration attributes without exporting arbitrary service response fields. */
    public static Map<String, String> attributes(Map<String, String> source, String... names) {
        var result = new TreeMap<String, String>();
        for (String name : names) if (source.containsKey(name)) result.put(name, source.get(name));
        return result;
    }

    private static Map<String, Object> copyConfiguration(Map<?, ?> source) {
        var copy = new TreeMap<String, Object>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), snapshot(value)));
        return copy;
    }

    private static Object snapshot(Object value) {
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(copyConfiguration(map));
        if (value instanceof List<?> list) return list.stream().map(ReportSection::snapshot).toList();
        return value;
    }

    private record ResourceKey(String type, String id) {}
    public record Resource(String type, String id, Map<String, Object> configuration) {
        public Resource { configuration = Collections.unmodifiableMap(copyConfiguration(configuration)); }
    }
    public record Issue(String operation, String resource, String kind, String message) {}
    public record Result(String service, String status, List<Resource> resources, List<Issue> issues) {
        public Result { resources = List.copyOf(resources); issues = List.copyOf(issues); }
    }
    private static final class RepeatedTokenException extends RuntimeException {}
}
