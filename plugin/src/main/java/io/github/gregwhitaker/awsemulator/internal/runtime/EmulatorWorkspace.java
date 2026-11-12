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

package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.gradle.api.GradleException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Owns the filesystem layout, inter-build locks, and guarded deletion of runtime homes. */
public final class EmulatorWorkspace {
    private static final String OWNER_FILE = ".gradle-aws-emulator-owner";
    private static final Map<Path, Lease> LEASES = new HashMap<>();
    private final EmulatorSettings settings;

    public EmulatorWorkspace(EmulatorSettings settings) { this.settings = settings; }

    public Path developmentHome() { return settings.workingDirectory().resolve("development"); }
    public Path managedHome(String suffix) { return settings.workingDirectory().resolve("managed-" + suffix); }
    public Path imageDirectory(String hash) { return settings.workingDirectory().resolve("images/" + hash); }
    public Path logsDirectory() { return settings.workingDirectory().resolve("logs"); }

    public Path managedLock(Path home) {
        return home.getParent().resolve("locks").resolve(home.getFileName() + ".lock");
    }

    /** Acquired before creating a managed container and held until cleanup, including startup failure. */
    public Lease tryManagedLease(Path home, boolean create) {
        try {
            return tryLease(managedLock(home), create);
        } catch (IOException e) {
            throw new GradleException("Cannot lock AWS Emulator managed instance at " + home, e);
        }
    }

    private Lease tryLease(Path path, boolean create) throws IOException {
        if (create) Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)) {
            throw new GradleException("Refusing to use a symlink for an emulator lease: " + path);
        }
        path = path.getParent().toRealPath().resolve(path.getFileName());
        synchronized (LEASES) {
            // On POSIX, closing another descriptor can release this JVM's existing file lock.
            // Avoid opening a second channel when this classloader already holds the lease.
            if (LEASES.containsKey(path)) return null;
            FileChannel channel = create
                ? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { lock = null; }
                if (lock != null) {
                    Lease lease = new Lease(path, channel, lock);
                    LEASES.put(path, lease);
                    return lease;
                }
                channel.close();
                return null;
            } catch (Exception e) {
                channel.close();
                throw e;
            }
        }
    }

    public record Lease(Path path, FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() {
            synchronized (LEASES) {
                try { channel.close(); }
                catch (IOException e) { throw new GradleException("Cannot release AWS Emulator lease.", e); }
                finally { LEASES.remove(path, this); }
            }
        }
    }

    public <T> T withLock(String name, Supplier<T> operation) {
        try {
            Files.createDirectories(settings.workingDirectory());
            try (Lease lease = tryLease(settings.workingDirectory().toRealPath().resolve(name), true)) {
                if (lease == null) throw new GradleException("Another build is managing this project's AWS Emulator. Retry after it finishes.");
                return operation.get();
            }
        } catch (IOException e) {
            throw new GradleException("Cannot access AWS Emulator working directory " + settings.workingDirectory(), e);
        }
    }

    public void prepareHome(Path home) {
        try {
            if (Files.isSymbolicLink(home)) throw new GradleException("Refusing to use a symlink as emulator home: " + home);
            Path marker = home.resolve(OWNER_FILE);
            if (Files.exists(home)) {
                if (Files.exists(marker)) requireOwner(home);
                else {
                    try (var files = Files.list(home)) {
                        if (files.findAny().isPresent()) {
                            throw new GradleException("Refusing to overwrite a nonempty directory not owned by this plugin: " + home);
                        }
                    }
                }
            }
            Files.createDirectories(home);
            if (!Files.exists(marker)) Files.writeString(marker, settings.owner(), StandardOpenOption.CREATE_NEW);
            Files.createDirectories(home.resolve(".aws/emulator"));
        } catch (IOException e) {
            throw new GradleException("Cannot prepare AWS Emulator home " + home, e);
        }
    }

    public void requireOwner(Path home) throws IOException {
        Path marker = home.resolve(OWNER_FILE);
        if (Files.isSymbolicLink(home) || Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)
                || !Files.readString(marker).equals(settings.owner())) {
            throw new GradleException("Refusing to modify an emulator directory not owned by this project: " + home);
        }
    }

    public void deleteHome(Path home) {
        if (!Files.exists(home)) return;
        try {
            requireOwner(home);
            Path marker = home.resolve(OWNER_FILE);
            try (var files = Files.walk(home)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!file.equals(home) && !file.equals(marker)) Files.deleteIfExists(file);
                }
            }
            Files.delete(marker);
            Files.delete(home);
        } catch (IOException e) {
            throw new GradleException("Container removed, but cannot delete generated files at " + home, e);
        }
    }
}
