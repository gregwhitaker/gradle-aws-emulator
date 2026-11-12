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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.gradle.api.GradleException;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JsonFixturesTest {
    @TempDir Path temp;
    @Test void rejectsMalformedDuplicateAndUnknownFieldsWithoutLeakingValues() throws Exception {
        Path fixture = temp.resolve("fixture.json");
        for (String json : new String[] {"[{\"name\":\"n\",\"value\":secret-value}]", "[{\"name\":\"a\",\"name\":\"b\"}]", "[{\"unknown\":\"secret-value\"}]", "[]", "[{}] true"}) {
            Files.writeString(fixture, json);
            var error = assertThrows(GradleException.class, () -> JsonFixtures.read(fixture, Set.of("name", "value")));
            assertFalse(error.toString().contains("secret-value")); assertNull(error.getCause());
        }
    }
    @Test void validatesWholeFixtureAndPreservesStringValues() throws Exception {
        Path fixture = temp.resolve("fixture.json");
        Files.writeString(fixture, "[{\"name\":\"a\",\"value\":\"{\\\"n\\\":1}\"}]");
        assertEquals("{\"n\":1}", JsonFixtures.string(JsonFixtures.read(fixture, Set.of("name", "value")).get(0), "value", null));
    }
}
