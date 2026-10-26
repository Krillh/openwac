package org.mod.openwac.core.device.storage;

import java.util.UUID;

/** Raw block media. Filesystems and paths belong in guest software. */
public interface BlockStorage {
    UUID id();
    int blockSize();
    long blockCount();
    int read(long firstBlock, int blockCount, byte[] destination, int offset);
    int write(long firstBlock, int blockCount, byte[] source, int offset);
    void flush();
}
