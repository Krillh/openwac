package org.mod.openwac.core.device.storage;

import java.util.Arrays;
import java.util.UUID;

/** Bounded in-memory media useful for small devices; snapshots are handled by the persistence adapter. */
public final class MemoryBlockStorage implements BlockStorage {
    private static final int MAX_CAPACITY = 64 * 1024 * 1024;
    private final UUID id;
    private final int blockSize;
    private final byte[] data;
    public MemoryBlockStorage(UUID id, int blockSize, int blockCount) {
        if (blockSize < 1 || blockCount < 1 || (long) blockSize * blockCount > MAX_CAPACITY) throw new IllegalArgumentException("Invalid media geometry");
        this.id = id; this.blockSize = blockSize; this.data = new byte[blockSize * blockCount];
    }
    @Override public UUID id() { return id; }
    @Override public int blockSize() { return blockSize; }
    @Override public long blockCount() { return data.length / blockSize; }
    @Override public synchronized int read(long first, int count, byte[] out, int offset) { return transfer(first, count, out, offset, false); }
    @Override public synchronized int write(long first, int count, byte[] in, int offset) { return transfer(first, count, in, offset, true); }
    private int transfer(long first, int count, byte[] bytes, int offset, boolean write) {
        if (first < 0 || count < 0 || offset < 0 || first + count > blockCount()) return -3;
        long n = (long) count * blockSize;
        if (n > Integer.MAX_VALUE || offset + n > bytes.length) return -3;
        int start = Math.toIntExact(first * blockSize), length = (int) n;
        if (write) System.arraycopy(bytes, offset, data, start, length); else System.arraycopy(data, start, bytes, offset, length);
        return length;
    }
    @Override public void flush() {}
    public synchronized byte[] copyContents() { return Arrays.copyOf(data, data.length); }
}
