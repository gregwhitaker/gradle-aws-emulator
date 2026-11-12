package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.gradle.api.GradleException;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Streams line-oriented message input without extracting archives or following directory symlinks. */
final class SqsMessageReader {
    private static final int MAX_BYTES = 1_048_576;

    void read(List<Path> inputs, Consumer<String> consumer) {
        for (Path input : inputs) {
            try {
                if (Files.isDirectory(input)) {
                    try (var paths = Files.walk(input)) {
                        for (Path file : paths.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
                                .filter(path -> !hidden(input.relativize(path).toString())).sorted().toList()) {
                            file(file, consumer);
                        }
                    }
                } else {
                    file(input, consumer);
                }
            } catch (IOException error) {
                throw new GradleException("Cannot read SQS messages from " + input + ".", error);
            }
        }
    }

    private void file(Path file, Consumer<String> consumer) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (InputStream input = Files.newInputStream(file)) {
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                try (var archive = new TarArchiveInputStream(new GzipCompressorInputStream(input))) {
                    TarArchiveEntry entry;
                    while ((entry = archive.getNextTarEntry()) != null) {
                        if (entry.isFile() && !hidden(entry.getName())) {
                            if (!archive.canReadEntryData(entry)) throw new IOException("Unsupported archive entry: " + entry.getName());
                            lines(archive, consumer);
                        }
                    }
                }
            } else {
                lines(input, consumer);
            }
        }
    }

    private void lines(InputStream input, Consumer<String> consumer) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(new FilterInputStream(input) {
            @Override public void close() { /* The caller owns the file or archive stream. */ }
        }, decoder))) {
            StringBuilder line = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                if (character == '\n' || character == '\r') {
                    emit(line, consumer);
                    line.setLength(0);
                } else {
                    if (line.length() >= MAX_BYTES) throw new GradleException("An SQS message exceeds the 1 MiB limit.");
                    line.append((char) character);
                }
            }
            emit(line, consumer);
        }
    }

    private void emit(StringBuilder line, Consumer<String> consumer) {
        if (line.isEmpty()) return;
        String body = line.toString();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new GradleException("An SQS message exceeds the 1 MiB limit.");
        if (body.codePoints().anyMatch(c -> !(c == 9 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF))) {
            throw new GradleException("An SQS message contains a character that SQS does not accept.");
        }
        consumer.accept(body);
    }

    private boolean hidden(String name) {
        return Arrays.stream(name.replace('\\', '/').split("/"))
            .anyMatch(part -> !part.equals(".") && part.startsWith("."));
    }
}
