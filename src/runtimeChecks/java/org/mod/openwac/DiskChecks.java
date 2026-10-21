package org.mod.openwac;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.device.storage.*;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import org.mod.openwac.service.FileStorageRepository;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistence and HAL integration checks for raw disk media. */
final class DiskChecks {
    private static final int BLOCK_SIZE = 2048, BLOCK_COUNT = 512;
    private static final UUID DISK_ID = new UUID(0, 2);
    private DiskChecks() {}
    static void run() throws Exception {
        Path root = Files.createTempDirectory("openwac-disk-check-");
        Path directory = root.resolve("disks");
        try {
            var repository = new FileStorageRepository(directory);
            var media = repository.open(DISK_ID, BLOCK_SIZE, BLOCK_COUNT);
            var disk = new DiskDevice(media);
            require(!Files.exists(directory), "Opening a component must not perform server-thread disk I/O");
            int size = ByteBuffer.wrap(call(disk, DiskDevice.BLOCK_SIZE, new byte[0], 4).response()).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int count = ByteBuffer.wrap(call(disk, DiskDevice.BLOCK_COUNT, new byte[0], 4).response()).order(ByteOrder.LITTLE_ENDIAN).getInt();
            require(size == BLOCK_SIZE && count == BLOCK_COUNT, "Tier 1 geometry");
            require((long) BLOCK_SIZE * BLOCK_COUNT == 1024 * 1024, "Tier 1 capacity must be exactly 1 MiB");
            require(!Files.exists(directory), "Discovery and INFO must not allocate a disk image");
            require(Arrays.equals(read(disk, 511, 1), new byte[BLOCK_SIZE]), "New disks must be zero initialized, including their last block");
            byte[] first = new byte[BLOCK_SIZE], last = new byte[BLOCK_SIZE];
            new Random(7).nextBytes(first); new Random(8).nextBytes(last);
            write(disk, 0, 1, first); write(disk, 511, 1, last);

            // Reopen without FLUSH: successful writes are already persisted, not retained in an instance cache.
            var reopened = new DiskDevice(new FileStorageRepository(directory).open(DISK_ID, BLOCK_SIZE, BLOCK_COUNT));
            require(Arrays.equals(read(reopened, 0, 1), first), "First block survives a new repository/device instance");
            require(Arrays.equals(read(reopened, 511, 1), last), "Last block survives reload");
            byte[] multiple = new byte[127 * BLOCK_SIZE]; new Random(9).nextBytes(multiple);
            write(reopened, 10, 127, multiple);
            require(Arrays.equals(read(disk, 10, 127), multiple), "Maximum 127-block transfer and shared UUID addressing");
            require(Arrays.equals(read(disk, 9, 1), new byte[BLOCK_SIZE]), "Writes must not affect adjacent blocks");
            require(call(disk, DiskDevice.READ_BLOCK, range(512, 1), BLOCK_SIZE).code() == -10, "Reading beyond capacity must fail");
            require(call(disk, DiskDevice.READ_BLOCK, range(Long.MAX_VALUE, 1), BLOCK_SIZE).code() == -10, "Huge block numbers must not overflow");
            require(call(disk, DiskDevice.READ_BLOCK, range(-1, 1), BLOCK_SIZE).code() == -10, "Negative block numbers must fail");
            require(call(disk, DiskDevice.READ_BLOCK, range(0, 1), BLOCK_SIZE - 1).code() == -5, "Short response buffer must fail");
            require(call(disk, DiskDevice.WRITE_BLOCK, range(0, 1), 0).code() == -4, "Short write payload must fail");
            require(Arrays.equals(read(disk, 0, 1), first), "Rejected writes must preserve existing data");
            media.flush();
            require(media.read(Long.MAX_VALUE, 1, new byte[BLOCK_SIZE], 0) == -3, "Backing store independently validates ranges");
            var wrongGeometry = new DiskDevice(repository.open(DISK_ID, 1024, 1024));
            require(call(wrongGeometry, DiskDevice.READ_BLOCK, range(0, 1), 1024).code() == -6, "Existing geometry mismatch must fail rather than reformat");
            require(Arrays.equals(read(disk, 0, 1), first), "Mismatched opens must preserve data");
            Path file = directory.resolve(DISK_ID + ".disk");
            require(Files.size(file) == 32 + 1024 * 1024, "Versioned disk image has exact geometry plus header");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                require(lock.isValid(), "Test lock must be held");
                require(call(disk, DiskDevice.READ_BLOCK, range(0, 1), BLOCK_SIZE).code() == -6, "Overlapping disk access must fail without blocking");
            }
            require(Arrays.equals(read(disk, 0, 1), first), "Disk remains accessible after lock contention");
            discoverFromWasm(disk);
            Files.write(file, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            require(call(disk, DiskDevice.READ_BLOCK, range(0, 1), BLOCK_SIZE).code() == -6, "A truncated existing image must fail rather than silently reformat");
            require(Files.size(file) == 0, "Opening corrupt media must not overwrite it");
            deletionChecks(directory);
            blankEjectionChecks(root.resolve("blank-disks"));
        } finally {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
                Files.delete(directory);
            }
            Files.delete(root);
        }
    }
    private static void blankEjectionChecks(Path directory) throws IOException {
        var repository = new FileStorageRepository(directory);
        UUID id = new UUID(0, 32);
        try {
            var media = repository.open(id, BLOCK_SIZE, BLOCK_COUNT);
            require(!repository.deleteIfZero(id, BLOCK_SIZE, BLOCK_COUNT) && !Files.exists(directory),
                    "Unused media and ejection must not allocate a backing directory or image");
            media.setRoot(true);
            require(repository.deleteIfZero(id, BLOCK_SIZE, BLOCK_COUNT), "Blank payload must be reclaimed despite the nonzero header");
            require(!Files.exists(directory.resolve(id + ".disk")), "Ejected blank image must be absent");
            require(Files.exists(directory.resolve(id + ".disk.root")) && media.isRoot(), "Root flag must survive blank reclamation and recreation");
            byte[] last = new byte[BLOCK_SIZE];
            last[BLOCK_SIZE - 1] = 7;
            media.write(BLOCK_COUNT - 1, 1, last, 0);
            require(!repository.deleteIfZero(id, BLOCK_SIZE, BLOCK_COUNT), "A nonzero byte at the end of media must prevent reclamation");
            byte[] readBack = new byte[BLOCK_SIZE];
            media.read(BLOCK_COUNT - 1, 1, readBack, 0);
            require(Arrays.equals(last, readBack), "Preserved media must retain every byte");
            media.write(BLOCK_COUNT - 1, 1, new byte[BLOCK_SIZE], 0);
            require(repository.deleteIfZero(id, BLOCK_SIZE, BLOCK_COUNT), "Overwriting all data with zero must make media reclaimable again");
            Files.write(directory.resolve(id + ".disk"), new byte[] {0});
            try {
                repository.deleteIfZero(id, BLOCK_SIZE, BLOCK_COUNT);
                throw new AssertionError("Corrupt media must not be treated as blank");
            } catch (UncheckedIOException expected) {
                require(Files.size(directory.resolve(id + ".disk")) == 1, "Corrupt media must be retained");
            }
        } finally {
            repository.delete(id);
            if (Files.isDirectory(directory)) Files.delete(directory);
        }
    }
    private static void deletionChecks(Path directory) throws IOException {
        var repository = new FileStorageRepository(directory);
        UUID deleted = new UUID(0, 30), retained = new UUID(0, 31);
        var media = repository.open(deleted, BLOCK_SIZE, BLOCK_COUNT);
        media.setRoot(true);
        var survivor = repository.open(retained, BLOCK_SIZE, BLOCK_COUNT);
        survivor.setRoot(true);
        repository.delete(deleted);
        require(!Files.exists(directory.resolve(deleted + ".disk"))
                && !Files.exists(directory.resolve(deleted + ".disk.root")), "Disposal removes media and root metadata");
        repository.delete(deleted);
        require(survivor.isRoot(), "Repeated disposal must preserve other disks");
        require(!repository.open(deleted, BLOCK_SIZE, BLOCK_COUNT).isRoot(), "Recreated media must not inherit a deleted root flag");
        repository.delete(deleted);
        Path blockedImage = directory.resolve(deleted + ".disk");
        Path metadata = directory.resolve(deleted + ".disk.root");
        Files.createDirectory(blockedImage);
        Files.write(blockedImage.resolve("blocker"), new byte[] {1});
        Files.write(metadata, new byte[] {1});
        try {
            repository.delete(deleted);
            throw new AssertionError("Deletion failures must be reported");
        } catch (UncheckedIOException expected) {
            require(!Files.exists(metadata), "Metadata deletion must still be attempted when image deletion fails");
        } finally {
            Files.delete(blockedImage.resolve("blocker"));
            Files.delete(blockedImage);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static DeviceResult call(DiskDevice disk, int operation, byte[] arguments, int capacity) {
        return disk.device_call(new DeviceCall(operation, arguments, capacity));
    }
    private static byte[] range(long first, int count) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) first).array();
    }
    private static void write(DiskDevice disk, long first, int count, byte[] bytes) {
        for (int i = 0; i < count; i++) {
            byte[] arguments = ByteBuffer.allocate(4 + BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt((int) first + i).put(bytes, i * BLOCK_SIZE, BLOCK_SIZE).array();
            require(call(disk, DiskDevice.WRITE_BLOCK, arguments, 0).code() == 0, "Disk write must succeed");
        }
    }
    private static byte[] read(DiskDevice disk, long first, int count) {
        byte[] bytes = new byte[count * BLOCK_SIZE];
        for (int i = 0; i < count; i++) {
            DeviceResult result = call(disk, DiskDevice.READ_BLOCK, range(first + i, 1), BLOCK_SIZE);
            require(result.code() == 0 && result.response().length == BLOCK_SIZE, "Disk read must return a whole block");
            System.arraycopy(result.response(), 0, bytes, i * BLOCK_SIZE, BLOCK_SIZE);
        }
        return bytes;
    }
    private static void discoverFromWasm(DiskDevice disk) {
        var bus = new DeviceBus();
        bus.attach(new BiosEeprom(new UUID(0, 1), "checks:unused", new FirmwareRepository() {
            @Override public byte[] load(String reference) { throw new UnsupportedOperationException(); }
            @Override public String install(byte[] bytes) { throw new UnsupportedOperationException(); }
        }));
        bus.attach(disk);
        require(bus.enumerate().get(1).id().equals(DISK_ID), "Disk enumeration must retain physical UUID");
        var host = new HostServices(bus, new EventHub(4), message -> true, new HostServices.ProcessControl() {
            @Override public long spawn(byte[] wasm, boolean privileged) { return 0; }
            @Override public boolean kill(long id) { return false; }
        }, RuntimeChecks.NO_METRICS);
        try (WasmProcess process = new ChicoryEngine().createProcess(discoveryModule(), new EngineLimits(1024 * 1024, 65536, 256), new org.mod.openwac.core.machine.MemoryBudget(65536), host, true)) {
            WasmProcess.Step step = process.runQuantum(256);
            require(step.reason() == WasmProcess.Reason.EXITED, "BIOS-style HAL enumeration must see the disk's interface ID and UUID: " + step);
        }
        bus.detach(DISK_ID);
        var anotherComputer = new DeviceBus(); anotherComputer.attach(disk);
        require(anotherComputer.enumerate().getFirst().id().equals(DISK_ID), "Moving the disk to another computer must preserve identity");
    }
    private static byte[] discoveryModule() {
        var output = new ByteArrayOutputStream(); output.writeBytes(new byte[] {0, 97, 115, 109, 1, 0, 0, 0});
        section(output, 1, new byte[] {3, 0x60, 0, 1, 0x7f, 0x60, 3, 0x7f, 0x7f, 0x7f, 1, 0x7f, 0x60, 0, 0});
        var imports = new ByteArrayOutputStream(); imports.write(2);
        name(imports, "hal"); name(imports, "hal_device_count"); imports.writeBytes(new byte[] {0, 0});
        name(imports, "hal"); name(imports, "hal_device_info"); imports.writeBytes(new byte[] {0, 1});
        section(output, 2, imports.toByteArray()); section(output, 3, new byte[] {1, 2}); section(output, 5, new byte[] {1, 1, 1, 1});
        var exports = new ByteArrayOutputStream(); exports.write(2);
        name(exports, "_start"); exports.writeBytes(new byte[] {0, 2}); name(exports, "memory"); exports.writeBytes(new byte[] {2, 0});
        section(output, 7, exports.toByteArray());
        var body = new ByteArrayOutputStream(); body.write(0);
        body.writeBytes(new byte[] {0x10, 0, 0x41, 2}); assertEqual(body); // Device count == 2.
        body.writeBytes(new byte[] {0x41, 1, 0x41, 0, 0x41, 20, 0x10, 1, 0x41, 0}); assertEqual(body); // INFO succeeds.
        body.writeBytes(new byte[] {0x41, 0, 0x28, 2, 0, 0x41, (byte) 0x80, 2}); assertEqual(body); // Interface ID == disk (0x0100).
        body.writeBytes(new byte[] {0x41, 19, 0x2d, 0, 0, 0x41, 2}); assertEqual(body); // Last UUID byte == 2.
        body.write(0x0b);
        var code = new ByteArrayOutputStream(); code.write(1); uleb(code, body.size()); code.writeBytes(body.toByteArray()); section(output, 10, code.toByteArray());
        return output.toByteArray();
    }
    private static void assertEqual(ByteArrayOutputStream body) { body.writeBytes(new byte[] {0x47, 0x04, 0x40, 0, 0x0b}); }
    private static void section(ByteArrayOutputStream out, int id, byte[] payload) { out.write(id); uleb(out, payload.length); out.writeBytes(payload); }
    private static void name(ByteArrayOutputStream out, String name) { byte[] bytes = name.getBytes(StandardCharsets.UTF_8); uleb(out, bytes.length); out.writeBytes(bytes); }
    private static void uleb(ByteArrayOutputStream out, int value) {
        do { int b = value & 127; value >>>= 7; out.write(b | (value == 0 ? 0 : 128)); } while (value != 0);
    }
}
