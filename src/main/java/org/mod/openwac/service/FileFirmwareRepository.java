package org.mod.openwac.service;

import org.mod.openwac.core.firmware.FirmwareRepository;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;

/** World-local content-addressed blobs plus explicitly registered firmware bundled with the mod. */
public final class FileFirmwareRepository implements FirmwareRepository {
    public static final String BOOTLOADER_BIOS = "openwac:bootloader-v1";
    public static final String DEFAULT_BIOS = "openwac:bootstrap-v1";
    public static final String DISK_TEST_BIOS = "openwac:disk-test-v1";
    public static final String IMPORTER_TEST_BIOS = "openwac:importer-test-v1";
    public static final String TTD_TEST_BIOS = "openwac:ttd-test-v1";
    public static final String IMPORTER_TTD_TEST_BIOS = "openwac:importer-ttd-test-v1";
    private final Path directory;
    private final Map<String, Supplier<InputStream>> bundled;

    public FileFirmwareRepository(Path directory, Map<String, Supplier<InputStream>> bundled) {
        this.directory = directory.toAbsolutePath().normalize();
        this.bundled = Map.copyOf(bundled);
    }
    @Override public byte[] load(String reference) throws IOException {
        Supplier<InputStream> builtin = bundled.get(reference);
        try (InputStream stream = builtin != null ? builtin.get() : Files.newInputStream(path(reference))) {
            if (stream == null) throw new FileNotFoundException("Bundled firmware missing: " + reference);
            byte[] bytes = stream.readNBytes(MAX_MODULE_BYTES + 1);
            validate(bytes);
            if (builtin == null && !reference.equals(hash(bytes))) throw new IOException("Firmware checksum mismatch");
            return bytes;
        }
    }
    @Override public synchronized String install(byte[] wasm) throws IOException {
        byte[] bytes = wasm.clone();
        validate(bytes);
        String reference = hash(bytes);
        Path target = path(reference);
        Files.createDirectories(directory);
        if (Files.exists(target)) {
            load(reference); // Verify existing data before reusing it.
            return reference;
        }
        Path temporary = Files.createTempFile(directory, "firmware-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
        } finally { Files.deleteIfExists(temporary); }
        return reference;
    }
    private Path path(String reference) throws IOException {
        if (reference == null || !reference.matches("sha256:[0-9a-f]{64}")) throw new IOException("Unknown firmware reference");
        return directory.resolve(reference.substring(7) + ".wasm");
    }
    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void validate(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes.length > MAX_MODULE_BYTES || bytes[0] != 0 || bytes[1] != 0x61 || bytes[2] != 0x73 || bytes[3] != 0x6d)
            throw new IOException("Invalid firmware size or WASM magic");
    }
}
