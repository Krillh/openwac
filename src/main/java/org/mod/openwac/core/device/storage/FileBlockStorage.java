package org.mod.openwac.core.device.storage;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Versioned raw media with no long-lived handles or unflushed Java-side write cache. */
public final class FileBlockStorage implements BlockStorage {
    // Bounded shared locks serialize access and disposal across handles for the same media.
    private static final Object[] MEDIA_LOCKS = new Object[256];
    static { Arrays.setAll(MEDIA_LOCKS, ignored -> new Object()); }
    private static final int MAGIC = 0x4f574453; // OWDS
    private static final int VERSION = 1, HEADER_BYTES = 32;
    private final Path file;
    private final UUID id;
    private final int blockSize, blockCount;

    /** The repository, never a guest path, supplies the location. Construction performs no I/O. */
    public FileBlockStorage(Path file, UUID id, int blockSize, int blockCount) {
        if (blockSize < 1 || blockCount < 1 || (long) blockSize * blockCount > 64 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid media geometry");
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.id = Objects.requireNonNull(id);
        this.blockSize = blockSize;
        this.blockCount = blockCount;
    }
    @Override public UUID id() { return id; }
    @Override public int blockSize() { return blockSize; }
    @Override public long blockCount() { return blockCount; }

    @Override public int read(long first, int count, byte[] destination, int offset) {
        int length = transferLength(first, count, destination, offset);
        if (length <= 0) return length;
        return access(channel -> {
            readFully(channel, ByteBuffer.wrap(destination, offset, length), HEADER_BYTES + first * blockSize);
            return length;
        });
    }
    @Override public int write(long first, int count, byte[] source, int offset) {
        int length = transferLength(first, count, source, offset);
        if (length <= 0) return length;
        return access(channel -> {
            writeFully(channel, ByteBuffer.wrap(source, offset, length), HEADER_BYTES + first * blockSize);
            channel.force(true); // Acknowledged writes survive ordinary saves, unloads, and restarts.
            return length;
        });
    }
    @Override public void flush() { access(channel -> { channel.force(true); return 0; }); }

    private int transferLength(long first, int count, byte[] bytes, int offset) {
        if (bytes == null || first < 0 || count < 0 || offset < 0 || first > blockCount || count > blockCount - first) return -3;
        long length = (long) count * blockSize;
        if ((long) offset + length > bytes.length) return -3;
        return (int) length;
    }
    @FunctionalInterface private interface Access { int run(FileChannel channel) throws IOException; }
    private int access(Access action) {
        synchronized (mediaLock()) { return lockedAccess(action); }
    }
    private Object mediaLock() { return MEDIA_LOCKS[file.hashCode() & (MEDIA_LOCKS.length - 1)]; }
    private int lockedAccess(Access action) {
        try {
            Files.createDirectories(file.getParent());
            createIfMissing();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Disk is busy");
                validate(channel);
                return action.run(channel);
            }
        } catch (OverlappingFileLockException busy) {
            throw new UncheckedIOException(new IOException("Disk is busy", busy));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    /** Remove an existing blank image without allocating media. */
    public boolean deleteIfZero() {
        synchronized (mediaLock()) {
            if (!Files.exists(file)) return false;
            try {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Disk is busy");
                    validate(channel);
                    ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
                    long end = HEADER_BYTES + (long) blockSize * blockCount;
                    for (long position = HEADER_BYTES; position < end;) {
                        bytes.clear().limit((int) Math.min(bytes.capacity(), end - position));
                        int length = bytes.remaining();
                        readFully(channel, bytes, position);
                        for (int index = 0; index < length; index++) if (bytes.get(index) != 0) return false;
                        position += length;
                    }
                }
                // Windows requires the image handle to be closed before unlinking it.
                return Files.deleteIfExists(file);
            } catch (OverlappingFileLockException busy) {
                throw new UncheckedIOException(new IOException("Disk is busy", busy));
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }
    private void createIfMissing() throws IOException {
        if (Files.exists(file)) return;
        Path temporary = Files.createTempFile(file.getParent(), id + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                initialize(channel);
            }
            // Same-directory publication without REPLACE_EXISTING preserves a concurrently created image.
            try { Files.move(temporary, file); }
            catch (FileAlreadyExistsException alreadyCreated) { /* Validate and use the existing image. */ }
        } finally { Files.deleteIfExists(temporary); }
    }
    private ByteBuffer header() {
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(VERSION).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putInt(blockSize).putInt(blockCount).flip();
    }
    private void initialize(FileChannel channel) throws IOException {
        writeFully(channel, header(), 0);
        ByteBuffer zeroes = ByteBuffer.allocate(64 * 1024);
        long end = HEADER_BYTES + (long) blockSize * blockCount;
        for (long position = HEADER_BYTES; position < end;) {
            zeroes.clear().limit((int) Math.min(zeroes.capacity(), end - position));
            int length = zeroes.remaining();
            writeFully(channel, zeroes, position);
            position += length;
        }
        channel.force(true);
    }
    private void validate(FileChannel channel) throws IOException {
        if (channel.size() != HEADER_BYTES + (long) blockSize * blockCount) throw new IOException("Invalid disk image length");
        ByteBuffer stored = ByteBuffer.allocate(HEADER_BYTES);
        readFully(channel, stored, 0);
        if (!stored.flip().equals(header())) throw new IOException("Disk UUID, geometry, or format does not match");
    }
    private static void readFully(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes, position);
            if (count <= 0) throw new EOFException("Incomplete disk read");
            position += count;
        }
    }
    private static void writeFully(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
        while (bytes.hasRemaining()) {
            int count = channel.write(bytes, position);
            if (count <= 0) throw new IOException("Incomplete disk write");
            position += count;
        }
    }
}
