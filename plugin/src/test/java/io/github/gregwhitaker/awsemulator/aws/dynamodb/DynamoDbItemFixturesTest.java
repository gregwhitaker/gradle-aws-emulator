package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbItemFixturesTest {
    @TempDir Path temp;

    @Test void readsAllAttributeTypesWithoutLosingNumberPrecision() throws Exception {
        Path file = temp.resolve("items.json");
        Files.writeString(file, """
            [{"id":{"S":"one"},"price":{"N":"12345678901234567890.123456789"},
              "binary":{"B":"AAH/"},"enabled":{"BOOL":false},"nothing":{"NULL":true},
              "tags":{"SS":["a","b"]},"numbers":{"NS":["1.0","2"]},"blobs":{"BS":["AA==","AQ=="]},
              "nested":{"M":{"values":{"L":[{"S":""},{"N":"4"},{"M":{}}]}}}}]
            """);
        var item = new DynamoDbItemFixtures().read(file).get(0);
        assertEquals("12345678901234567890.123456789", item.get("price").n());
        assertArrayEquals(new byte[] {0, 1, -1}, item.get("binary").b().asByteArray());
        assertFalse(item.get("enabled").bool());
        assertTrue(item.get("nothing").nul());
        assertEquals(List.of("1", "2"), item.get("numbers").ns());
        assertEquals(2, item.get("tags").ss().size());
        assertEquals(2, item.get("blobs").bs().size());
        assertEquals(3, item.get("nested").m().get("values").l().size());
    }

    @Test void rejectsMalformedAmbiguousOrInvalidAttributesWithoutIncludingTheirValues() throws Exception {
        Path file = temp.resolve("items.json");
        for (String value : List.of("\"private-value\"", "{}", "{\"S\":\"private-value\",\"N\":\"1\"}",
                "{\"N\":1}", "{\"N\":\"private-value\"}", "{\"N\":\"1e126\"}", "{\"B\":\"private-value\"}",
                "{\"BOOL\":\"private-value\"}", "{\"NULL\":false}", "{\"SS\":[]}", "{\"SS\":[\"\"]}",
                "{\"NS\":[\"1\",\"1.0\"]}", "{\"S\":\"private-value\",\"S\":\"duplicate\"}", "{\"UNKNOWN\":\"private-value\"}")) {
            Files.writeString(file, "[{\"id\":" + value + "}]");
            var error = assertThrows(GradleException.class, () -> new DynamoDbItemFixtures().read(file), value);
            assertFalse(error.toString().contains("private-value"));
            assertNull(error.getCause());
        }
    }
}
