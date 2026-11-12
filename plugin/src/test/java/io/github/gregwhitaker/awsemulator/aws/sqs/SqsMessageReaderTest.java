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

package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqsMessageReaderTest {
    @TempDir Path directory;

    @Test
    void readsUtf8LinesPreservingWhitespaceAndSkippingEmptyLines() throws Exception {
        Path file = Files.writeString(directory.resolve("messages.txt"), "first\r\n\n  spaced  \rüñicode 😀\nlast");
        assertEquals(List.of("first", "  spaced  ", "üñicode 😀", "last"), read(file));
    }

    @Test
    void readsDirectoriesRecursivelyInOrderWithoutHiddenFilesOrSymlinks() throws Exception {
        Files.createDirectory(directory.resolve("nested"));
        Files.writeString(directory.resolve("b.txt"), "second");
        Files.writeString(directory.resolve("a.txt"), "first");
        Files.writeString(directory.resolve("nested/c.txt"), "third");
        Files.writeString(directory.resolve(".hidden"), "ignore");
        Files.createDirectory(directory.resolve(".hidden-directory"));
        Files.writeString(directory.resolve(".hidden-directory/data"), "ignore");
        Files.createSymbolicLink(directory.resolve("linked.txt"), directory.resolve("a.txt"));
        assertEquals(List.of("first", "second", "third"), read(directory));
    }

    @Test
    void readsTarGzAndTgzEntriesWithoutExtractingThemOrIncludingHiddenEntries() throws Exception {
        for (String extension : List.of("tar.gz", "tgz")) {
            Path archive = archive("messages." + extension, Map.of(
                "nested/messages.txt", "one\ntwo", "next.txt", "three", "nested/.hidden", "ignore", ".ignored/file", "ignore"));
            assertEquals(List.of("one", "three", "two"), read(archive).stream().sorted().toList());
            assertFalse(Files.exists(directory.resolve("nested")));
        }
    }

    @Test
    void reportsMissingFilesCorruptArchivesAndInvalidUtf8() throws Exception {
        assertThrows(GradleException.class, () -> read(directory.resolve("missing")));
        Path invalidArchive = Files.writeString(directory.resolve("broken.tgz"), "not gzip");
        assertThrows(GradleException.class, () -> read(invalidArchive));
        Path invalidUtf8 = Files.write(directory.resolve("bad-utf8.txt"), new byte[] {(byte) 0xC3, 0x28});
        assertThrows(GradleException.class, () -> read(invalidUtf8));
    }

    @Test
    void rejectsInvalidSqsCharactersAndMessagesOverTheUtf8ByteLimit() throws Exception {
        Path control = Files.writeString(directory.resolve("control.txt"), "bad\u0001message");
        assertThrows(GradleException.class, () -> read(control));
        Path tooLarge = Files.writeString(directory.resolve("large.txt"), "é".repeat(524289));
        assertTrue(assertThrows(GradleException.class, () -> read(tooLarge)).getMessage().contains("1 MiB"));
        Path maximum = Files.writeString(directory.resolve("max.txt"), "a".repeat(1048576));
        assertEquals(1048576, read(maximum).get(0).length());
    }

    private List<String> read(Path path) {
        var messages = new ArrayList<String>();
        new SqsMessageReader().read(List.of(path), messages::add);
        return messages;
    }

    private Path archive(String name, Map<String, String> files) throws Exception {
        Path path = directory.resolve(name);
        try (var output = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(path)))) {
            for (var file : files.entrySet()) {
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(file.getKey());
                entry.setSize(bytes.length);
                output.putArchiveEntry(entry);
                output.write(bytes);
                output.closeArchiveEntry();
            }
        }
        return path;
    }
}
