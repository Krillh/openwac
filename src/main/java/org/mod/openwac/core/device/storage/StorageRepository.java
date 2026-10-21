package org.mod.openwac.core.device.storage;

import java.util.UUID;

/** Resolves persistent media by physical device identity. Opening a handle need not perform I/O. */
@FunctionalInterface
public interface StorageRepository {
    BlockStorage open(UUID id, int blockSize, int blockCount);
}
