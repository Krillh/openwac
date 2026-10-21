package org.mod.openwac.core.device.importer;

import org.mod.openwac.core.device.*;
import static org.mod.openwac.core.device.DeviceStatus.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;

/** Read-only host-selected file. Guest arguments never choose or change host paths. */
public final class FileImporterDevice implements Device {
    public static final InterfaceId INTERFACE = new InterfaceId(0x0200, "openwac:file_importer", 1, 0);
    public static final int FILE_SIZE = 0, READ_BLOCK = 1, FILE_NAME = 2, FILE_NAME_LENGTH = 3;
    public static final int BLOCK_SIZE = 256;
    private final UUID id;
    public record Source(Path path, String filename) {}
    private final Supplier<Source> source;
    private Path file;
    private byte[] name;
    private long size;
    private boolean initialized;

    /** Construction is I/O-free. Metadata is captured on the first worker-side invocation. */
    public FileImporterDevice(UUID id, Path absolutePath) {
        this(id, () -> new Source(absolutePath, absolutePath == null ? "" : absolutePath.getFileName().toString()));
    }
    public FileImporterDevice(UUID id, Supplier<Source> source) {
        this.id = Objects.requireNonNull(id);
        this.source = Objects.requireNonNull(source);
    }
    @Override public UUID id() { return id; }
    @Override public InterfaceId interfaceId() { return INTERFACE; }
    private void initialize() throws IOException {
        if (initialized) return;
        // A missing file remains broken until this device is recreated (power cycle).
        initialized = true;
        Source selected = source.get();
        Path configuredPath = selected.path();
        if (configuredPath == null || !configuredPath.isAbsolute()) throw new IOException("Importer has no configured file");
        Path resolved = configuredPath.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(resolved, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Importer requires a regular file");
        String basename = selected.filename();
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(basename)) throw new IOException("Importer filename must be ASCII");
        name = basename.getBytes(StandardCharsets.US_ASCII);
        size = attributes.size();
        file = resolved;
    }
    private DeviceResult response(DeviceCall call, byte[] bytes) {
        return call.responseLimit() < bytes.length ? DeviceResult.error(RESPONSE_OVERRUN) : DeviceResult.ok(bytes);
    }
    @Override public synchronized DeviceResult device_call(DeviceCall call) {
        byte[] args = call.arguments();
        if (call.operation() < FILE_SIZE || call.operation() > FILE_NAME_LENGTH) return DeviceResult.error(INVALID_OPERATION);
        if (call.operation() == READ_BLOCK) {
            if (args.length < 4) return DeviceResult.error(ARGUMENT_OVERRUN);
            if (args.length != 4) return DeviceResult.error(INVALID_ARGUMENT);
        } else if (args.length != 0) return DeviceResult.error(INVALID_ARGUMENT);
        try {
            initialize();
            if (file == null) return DeviceResult.error(DEVICE_BROKEN);
            return switch (call.operation()) {
                case FILE_SIZE -> response(call, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array());
                case FILE_NAME -> response(call, name);
                case FILE_NAME_LENGTH -> response(call, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(name.length).array());
                case READ_BLOCK -> {
                    long offset = Integer.toUnsignedLong(ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN).getInt()) * BLOCK_SIZE;
                    if (offset >= size) yield DeviceResult.error(OUT_OF_BOUNDS);
                    int count = (int) Math.min(BLOCK_SIZE, size - offset);
                    if (call.responseLimit() < count) yield DeviceResult.error(RESPONSE_OVERRUN);
                    byte[] bytes = new byte[count];
                    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                        if (channel.size() < size) yield DeviceResult.error(DEVICE_BROKEN);
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) {
                            int read = channel.read(buffer, offset + buffer.position());
                            if (read <= 0) throw new IOException("Incomplete importer read");
                        }
                    }
                    yield DeviceResult.ok(bytes);
                }
                default -> DeviceResult.error(INVALID_OPERATION);
            };
        } catch (IOException | java.io.UncheckedIOException | SecurityException failure) { return DeviceResult.error(DEVICE_BROKEN); }
    }
    @Override public DeviceSnapshot snapshot() {
        // Host paths are intentionally not exposed in guest metadata.
        return new DeviceSnapshot(1, id, "openwac:file_importer", Map.of());
    }
}
