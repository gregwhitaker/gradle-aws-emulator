package io.github.gregwhitaker.awsemulator.aws.internal;

import org.gradle.api.GradleException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Execution-time files for local fixtures and SDK results. */
public final class ResourceFiles {
    private ResourceFiles() {}
    public static byte[] read(Path source, Path target, int maxBytes) {
        try {
            if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())
                    || Files.exists(target) && Files.isSameFile(source, target)) throw new GradleException("Input and output files must differ.");
            long size = Files.size(source);
            if (size < 1 || size > maxBytes) throw new GradleException("Input file must contain 1–" + maxBytes + " bytes.");
            return Files.readAllBytes(source);
        } catch (GradleException e) { throw e; }
        catch (Exception e) { throw new GradleException("Cannot read input file.", e); }
    }
    public static void write(Path path, byte[] data) {
        Path temp = null;
        try {
            path = path.toAbsolutePath();
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), ".emulator-", ".tmp");
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            Files.write(temp, data);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new GradleException("Cannot write resource output file.", e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }
}
