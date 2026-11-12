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

import org.gradle.api.GradleException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Shared validation and bounded traversal for resource tasks. */
public final class ServiceSupport {
    private ServiceSupport() {}

    public static String name(String value, String pattern, String label) {
        if (value == null || !value.matches(pattern)) throw new GradleException("Invalid " + label + ".");
        return value;
    }

    public static String text(String value, String label) {
        if (value == null || value.isBlank()) throw new GradleException(label + " must not be blank.");
        return value;
    }

    public static List<String> names(List<String> names, String pattern, String label) {
        if (names.isEmpty()) throw new GradleException(label + " must not be empty.");
        var unique = new HashSet<String>();
        for (String name : names) {
            name(name, pattern, label);
            if (!unique.add(name)) throw new GradleException("Duplicate " + label + ": " + name);
        }
        return List.copyOf(names);
    }

    public static void positive(int seconds) {
        if (seconds < 1) throw new GradleException("waitTimeoutSeconds must be positive.");
    }

    public static void await(String operation, int seconds, BooleanSupplier ready) {
        positive(seconds);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(seconds).toNanos();
        do {
            if (ready.getAsBoolean()) return;
            if (System.nanoTime() >= deadline) break;
            try { Thread.sleep(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GradleException("Interrupted while " + operation, e); }
        } while (System.nanoTime() < deadline);
        throw new GradleException("Timed out after " + seconds + " seconds while " + operation + ".");
    }

    /** Guards APIs whose final page has a null/empty token. */
    public static String next(String token, Set<String> seen) {
        if (token == null || token.isEmpty()) return null;
        if (!seen.add(token)) throw new GradleException("Service listing returned a repeated continuation token.");
        return token;
    }
}
