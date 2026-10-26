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
    /** Ejected blank disks need no image. */
    public boolean deleteIfZero(UUID id, int blockSize, int blockCount) {
        return new FileBlockStorage(directory.resolve(Objects.requireNonNull(id) + ".disk"), id, blockSize, blockCount).deleteIfZero();
    }
    /** Permanent media disposal. A missing image is harmless. */
    public void delete(UUID id) {
        Path file = directory.resolve(Objects.requireNonNull(id) + ".disk");
        try { Files.deleteIfExists(file); }
        catch (IOException failure) {
            throw new UncheckedIOException("Could not delete disk backing file " + file, failure);
        }
    }
}
