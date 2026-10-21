package org.mod.openwac.service;

import org.mod.openwac.core.device.importer.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** World-owned immutable upload blobs and atomic per-device selection records. Worker I/O only. */
public final class ImportedFileStore implements FileImporterRepository {
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final long MAX_WORLD_BYTES = 256L * 1024 * 1024;
    private final Path root;
    public ImportedFileStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    @Override public FileImporterDevice open(UUID id) {
        return new FileImporterDevice(id, () -> {
            try { return load(id); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
    }
    private FileImporterDevice.Source load(UUID id) throws IOException {
        Path metadata = root.resolve(id + ".meta");
        if (Files.size(metadata) > 1024) throw new IOException("Invalid importer metadata");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(metadata))) {
            if (input.readInt() != 1) throw new IOException("Unsupported importer metadata");
            String hash = input.readUTF(), name = input.readUTF();
            long size = input.readLong();
            if (!hash.matches("[0-9a-f]{64}") || !validName(name) || size < 0 || size > MAX_FILE_BYTES)
                throw new IOException("Invalid importer metadata");
            Path blob = root.resolve(hash + ".blob");
            if (Files.size(blob) != size) throw new IOException("Importer content is missing or truncated");
            return new FileImporterDevice.Source(blob, name);
        }
    }
    public static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c > 126 || c == '/' || c == '\\') return false;
        }
        return true;
    }
    public Path createTemporary() throws IOException {
        Files.createDirectories(root);
        return Files.createTempFile(root, "upload-", ".part");
    }
    /** Called by the single upload worker. Existing snapshots keep their immutable blob. */
    public void publish(UUID id, String filename, long size, String hash, Path temporary) throws IOException {
        if (!validName(filename) || !hash.matches("[0-9a-f]{64}") || size < 0 || size > MAX_FILE_BYTES
                || Files.size(temporary) != size) throw new IOException("Invalid upload");
        Path blob = root.resolve(hash + ".blob");
        if (!Files.exists(blob)) {
            long used = 0;
            try (var files = Files.newDirectoryStream(root, "*.blob")) {
                for (Path file : files) used = Math.addExact(used, Files.size(file));
            }
            if (size > MAX_WORLD_BYTES - used) throw new IOException("World importer storage limit reached (256 MiB)");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, blob); // Hash is server-computed, not client-supplied.
        } else if (Files.size(blob) != size) throw new IOException("Stored importer blob is corrupt");
        Path pending = Files.createTempFile(root, "selection-", ".part");
        try {
            try (var output = new DataOutputStream(Files.newOutputStream(pending))) {
                output.writeInt(1); output.writeUTF(hash); output.writeUTF(filename); output.writeLong(size);
            }
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
            // Keep the old selection intact if atomic publication is unsupported or fails.
            Files.move(pending, root.resolve(id + ".meta"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
    public void cleanTemporaryFiles() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.newDirectoryStream(root, "*.part")) {
            for (Path file : files) Files.deleteIfExists(file);
        }
    }
}
