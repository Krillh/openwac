package org.mod.openwac.service;

import org.mod.openwac.core.device.storage.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;

/** World-scoped backing images. Moving an item between computers keeps its UUID-addressed media. */
public final class FileStorageRepository implements StorageRepository {
    private final Path directory;
    public FileStorageRepository(Path directory) { this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize(); }
    @Override public BlockStorage open(UUID id, int blockSize, int blockCount) {
        return new FileBlockStorage(directory.resolve(Objects.requireNonNull(id) + ".disk"), id, blockSize, blockCount);
    }
    /** Ejected blank disks need no image. Root metadata remains meaningful on blank media. */
    public boolean deleteIfZero(UUID id, int blockSize, int blockCount) {
        return new FileBlockStorage(directory.resolve(Objects.requireNonNull(id) + ".disk"), id, blockSize, blockCount).deleteIfZero();
    }
    /** Permanent media disposal. Missing files are harmless; attempt both even if one fails. */
    public void delete(UUID id) {
        String name = Objects.requireNonNull(id) + ".disk";
        IOException failure = null;
        for (String suffix : new String[] {"", ".root"}) {
            Path file = directory.resolve(name + suffix);
            try { Files.deleteIfExists(file); }
            catch (IOException problem) {
                var detail = new IOException("Could not delete disk backing file " + file, problem);
                if (failure == null) failure = detail;
                else failure.addSuppressed(detail);
            }
        }
        if (failure != null) throw new UncheckedIOException(failure);
    }
}
