package org.mod.openwac;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.device.storage.*;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import org.mod.openwac.service.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the shipped firmware, including its failure and persistence paths, through the real HAL. */
final class DiskBootloaderChecks {
    private static final int BLOCK_SIZE = 2048, BLOCK_COUNT = 512;
    private static final UUID DISK_ID = new UUID(0, 2);
    private DiskBootloaderChecks() {}
    static void run() throws Exception {
        Path root = Files.createTempDirectory("openwac-bootloader-check-");
        Path directory = root.resolve("disks");
        try {
            var repository = new FileStorageRepository(directory);
            var media = repository.open(DISK_ID, BLOCK_SIZE, BLOCK_COUNT);
            byte[] originalScratch = new byte[BLOCK_SIZE], bootSector = new byte[BLOCK_SIZE];
            new Random(51).nextBytes(originalScratch); new Random(52).nextBytes(bootSector);
            media.write(510, 1, originalScratch, 0); media.write(0, 1, bootSector, 0);
            var disk = new DiskDevice(media);
            List<String> first = boot(root, List.of(disk), true);
            expect(first, "GEOMETRY PASS:"); expect(first, "I/O PASS:"); expect(first, "BOUNDS PASS:");
            expect(first, "PERSISTENCE ARMED:"); expect(first, "DISK SELF-TEST PASS");
            expect(first, "SELF-TEST COMPLETE: 1 passed, 0 failed.");
            require(Arrays.equals(read(media, 510), originalScratch), "Self-test restores pre-existing scratch bytes");
            require(Arrays.equals(read(media, 0), bootSector), "Self-test must not touch the guest boot sector");
            Path image = directory.resolve(DISK_ID + ".disk");
            byte[] firstImage = Files.readAllBytes(image);

            var reloadedMedia = new FileStorageRepository(directory).open(DISK_ID, BLOCK_SIZE, BLOCK_COUNT);
            List<String> second = boot(root, List.of(new DiskDevice(reloadedMedia)), false);
            expect(second, "PERSISTENCE PASS:");
            require(second.stream().noneMatch(text -> text.startsWith("PERSISTENCE ARMED:")), "Second boot verifies existing marker rather than replacing it");
            require(Arrays.equals(firstImage, Files.readAllBytes(image)), "Successful subsequent tests preserve the complete disk image");

            List<String> absent = boot(root, List.of(), false);
            expect(absent, "NO DISK:"); expect(absent, "SELF-TEST COMPLETE: 0 passed, 0 failed.");

            var unsupportedCalls = new AtomicInteger();
            Device readOnly = new Device() {
                @Override public UUID id() { return DISK_ID; }
                @Override public InterfaceId interfaceId() { return DiskDevice.INTERFACE; }
                @Override public DeviceSnapshot snapshot() { return disk.snapshot(); }
                @Override public DeviceResult device_call(DeviceCall call) {
                    if (call.operation() != DiskDevice.BLOCK_SIZE && call.operation() != DiskDevice.BLOCK_COUNT) { unsupportedCalls.incrementAndGet(); return DeviceResult.error(-3); }
                    return DeviceResult.ok(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(call.operation() == DiskDevice.BLOCK_SIZE ? 256 : BLOCK_COUNT).array());
                }
            };
            List<String> unsupported = boot(root, List.of(readOnly), false);
            expect(unsupported, "FAIL: expected tier 1 geometry");
            expect(unsupported, "SELF-TEST COMPLETE: 0 passed, 1 failed.");
            require(unsupportedCalls.get() == 0, "Unsupported/read-only geometry must be rejected before any disk transfers");

            // Inject one successful read with corrupted bytes, after the temporary pattern was written.
            var written = new AtomicBoolean(); var corruptNext = new AtomicBoolean(true);
            Device faulty = new Device() {
                @Override public UUID id() { return disk.id(); }
                @Override public InterfaceId interfaceId() { return disk.interfaceId(); }
                @Override public DeviceSnapshot snapshot() { return disk.snapshot(); }
                @Override public DeviceResult device_call(DeviceCall call) {
                    byte[] arguments = call.arguments();
                    boolean scratch = arguments.length >= 4 && ByteBuffer.wrap(arguments).order(ByteOrder.LITTLE_ENDIAN).getInt() == 510;
                    DeviceResult result = disk.device_call(call);
                    if (scratch && call.operation() == DiskDevice.WRITE_BLOCK) written.set(true);
                    if (scratch && call.operation() == DiskDevice.READ_BLOCK && written.get() && corruptNext.compareAndSet(true, false) && result.code() == 0) {
                        byte[] response = result.response(); response[17] ^= 1;
                        return DeviceResult.ok(response);
                    }
                    return result;
                }
            };
            List<String> failed = boot(root, List.of(faulty), false);
            expect(failed, "FAIL: scratch readback mismatch"); expect(failed, "Scratch block restored after failure.");
            expect(failed, "SELF-TEST COMPLETE: 0 passed, 1 failed.");
            require(failed.stream().noneMatch(text -> text.equals("DISK SELF-TEST PASS")), "Failed I/O must not report success");
            require(Arrays.equals(firstImage, Files.readAllBytes(image)), "Readback failure path still restores the complete original disk");

            // A different disk's nonblank last block must remain untouched, while all disks are enumerated.
            var otherMedia = repository.open(new UUID(0, 3), BLOCK_SIZE, BLOCK_COUNT);
            byte[] occupied = new byte[BLOCK_SIZE]; new Random(53).nextBytes(occupied);
            otherMedia.write(511, 1, occupied, 0);
            List<String> multiple = boot(root, List.of(new DiskDevice(otherMedia), disk), false);
            expect(multiple, "PERSISTENCE SKIP:"); expect(multiple, "PERSISTENCE PASS:");
            expect(multiple, "SELF-TEST COMPLETE: 2 passed, 0 failed.");
            require(Arrays.equals(read(otherMedia, 511), occupied), "Occupied last block is never overwritten");
        } finally {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
                Files.delete(directory);
            }
            Files.delete(root);
        }
    }
    private static byte[] read(BlockStorage media, long block) {
        byte[] bytes = new byte[BLOCK_SIZE];
        require(media.read(block, 1, bytes, 0) == BLOCK_SIZE, "Fixture read");
        return bytes;
    }
    private static List<String> boot(Path root, List<? extends Device> disks, boolean rateLimited) throws Exception {
        var repository = new FileFirmwareRepository(root.resolve("firmware"), Map.of(FileFirmwareRepository.DISK_TEST_BIOS,
                () -> DiskBootloaderChecks.class.getResourceAsStream("/data/openwac/firmware/disk-test-v1.wasm")));
        var bios = new BiosEeprom(new UUID(0, 1), FileFirmwareRepository.DISK_TEST_BIOS, repository);
        var bus = new DeviceBus(); bus.attach(bios); disks.forEach(bus::attach);
        List<String> output = new ArrayList<>();
        var attempts = new AtomicInteger(); var complete = new AtomicBoolean();
        var host = new HostServices(bus, new EventHub(4), text -> {
            if (rateLimited && attempts.incrementAndGet() % 3 != 0) return false;
            output.add(text);
            if (text.startsWith("SELF-TEST COMPLETE:")) complete.set(true);
            return true;
        }, new HostServices.ProcessControl() {
            @Override public long spawn(byte[] wasm, boolean privileged) { throw new AssertionError("Self-test must not spawn guest processes"); }
            @Override public boolean kill(long id) { throw new AssertionError("Self-test must not kill guest processes"); }
        }, RuntimeChecks.NO_METRICS);
        try (WasmProcess process = new ChicoryEngine().createProcess(bios.bootloader(), new EngineLimits(1024 * 1024, 65536, 256), new org.mod.openwac.core.machine.MemoryBudget(65536), host, true)) {
            for (int turn = 0; turn < 20000 && !complete.get(); turn++) {
                WasmProcess.Step step = process.runQuantum(256);
                require(step.reason() != WasmProcess.Reason.TRAPPED && step.reason() != WasmProcess.Reason.EXITED,
                        "Disk test BIOS stopped unexpectedly: " + step + "; output=" + output);
            }
            require(complete.get(), "Disk test BIOS must finish bounded tests: " + output);
        }
        return output;
    }
    private static void expect(List<String> output, String prefix) {
        require(output.stream().anyMatch(text -> text.startsWith(prefix)), "Expected '" + prefix + "' in " + output);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
