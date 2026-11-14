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
import org.gradle.api.logging.Logging;
import java.io.IOException;
import java.nio.file.*;

/** Bounded fixture artifacts with strict parsing and atomic replacement after a complete export. */
public final class FixtureFiles {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private FixtureFiles() {}

    public static JsonNode read(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            checkSize(bytes.length);
            JsonNode node = JSON.readTree(bytes);
            if (node == null) throw new GradleException("Fixture must contain JSON (values omitted).");
            return node;
        } catch (GradleException e) { throw e; }
        catch (IOException e) { throw new GradleException("Cannot read fixture; check file access and JSON syntax (values omitted)."); }
    }

    public static void write(Path output, JsonNode contents) {
        Path temporary = null;
        try {
            byte[] bytes = JSON.writeValueAsBytes(contents);
            checkSize(bytes.length);
            Path target = output.toAbsolutePath();
            Path parent = target.getParent();
            if (parent == null) throw new GradleException("Fixture output must be a file with a parent directory.");
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".aws-fixture-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new GradleException("Cannot write exported fixture (values omitted).");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException e) { Logging.getLogger(FixtureFiles.class).warn("Could not remove temporary fixture file: {}", temporary); }
        }
    }

    public static void checkSize(long bytes) {
        if (bytes > MAX_BYTES) throw new GradleException("Fixture exceeds the 16 MiB limit; export a smaller dataset.");
    }

    /** Accounts for each serialized entry while collecting, before retaining an unbounded dataset. */
    public static final class Budget {
        private long bytes = 2;
        public void add(JsonNode entry) {
            try { bytes += JSON.writeValueAsBytes(entry).length + 1L; }
            catch (IOException e) { throw new GradleException("Cannot encode fixture (values omitted)."); }
            checkSize(bytes);
        }
    }
}
