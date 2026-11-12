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
