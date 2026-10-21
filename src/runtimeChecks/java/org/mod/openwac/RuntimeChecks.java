package org.mod.openwac;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.process.*;
import org.mod.openwac.core.machine.*;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import org.mod.openwac.service.FileFirmwareRepository;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Standalone checks keep runtime verification independent of Minecraft startup and test libraries. */
public final class RuntimeChecks {
    static final HostServices.MachineMetrics NO_METRICS = new HostServices.MachineMetrics() {
        @Override public long cpuTimeNanos() { return 0; }
        @Override public long ramUsedPages() { return 0; }
    };
    private static final byte[] HEADER = {0, 97, 115, 109, 1, 0, 0, 0};
    private static final EngineLimits LIMITS = new EngineLimits(1024 * 1024, 8 * 1024 * 1024, 256);
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--disks-only")) {
            DiskChecks.run();
            System.out.println("OpenWAC disk checks passed");
            return;
        }
        if (args.length == 1 && args[0].equals("--kernel-reports-only")) {
            org.mod.openwac.runtime.chicory.KernelChecks.reportChecks();
            System.out.println("OpenWAC kernel report checks passed");
            return;
        }
        if (args.length == 1 && args[0].equals("--crash-reports-only")) {
            CrashChecks.run();
            System.out.println("OpenWAC crash report checks passed");
            return;
        }
        CrashChecks.run();
        roundRobinAndYield();
        tierOneCpuLimits();
        org.mod.openwac.runtime.chicory.MemoryChecks.run();
        org.mod.openwac.runtime.chicory.KernelChecks.reportChecks();
        org.mod.openwac.runtime.chicory.KernelChecks.run();
        deviceIdentityAndDispatch();
        TtdChecks.run();
        MetricsChecks.run();
        firmwareDeduplication();
        continuationsAndPreemption();
        hostileDeclarationsAndPointers();
        bootAndColdRestore();
        DiskChecks.run();
        DiskBootloaderChecks.run();
        System.out.println("OpenWAC runtime checks passed");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void roundRobinAndYield() {
        List<String> turns = new ArrayList<>();
        ProcessManager manager = new ProcessManager(3);
        ProcessFactory factory = (id, wasm, privileged) -> new WasmProcess() {
            @Override public Step runQuantum(long fuel) {
                turns.add(id + ":" + fuel);
                return new Step(id == 2 ? 1 : fuel, id == 2 ? Reason.YIELDED : Reason.QUANTUM_EXHAUSTED, null);
            }
            @Override public void wake() {}
            @Override public void close() {}
        };
        try (manager) {
            manager.spawn(HEADER, true, factory); manager.spawn(HEADER, true, factory); manager.spawn(HEADER, true, factory);
            require(manager.spawn(HEADER, true, factory) == 0, "Process cap must include pending spawns");
            var tick = manager.runTick(10, 4);
            require(tick.fuelUsed() == 10, "Tick fuel accounting");
            require(turns.equals(List.of("1:4", "2:4", "3:4", "1:1")), "Round robin must refund unused quantum and use the final partial quantum: " + turns);
            turns.clear(); manager.runTick(5, 4);
            require(turns.equals(List.of("2:4", "3:4")), "Next tick must retain the ready-queue cursor: " + turns);
        }
    }
    private static void tierOneCpuLimits() {
        var config = new MachineConfig(65_536, 256, 16, 64L * 1024 * 1024);
        Map<Long, Long> consumed = new LinkedHashMap<>();
        ProcessFactory factory = (id, wasm, privileged) -> new WasmProcess() {
            @Override public Step runQuantum(long fuel) {
                consumed.merge(id, fuel, Long::sum);
                return new Step(fuel, Reason.QUANTUM_EXHAUSTED, null);
            }
            @Override public void wake() {}
            @Override public void close() {}
        };
        try (var manager = new ProcessManager(config.maxProcesses())) {
            for (int i = 0; i < 16; i++)
                require(manager.spawn(HEADER, i == 0, factory) != 0, "CPU must admit BIOS plus 15 child processes");
            require(manager.spawn(HEADER, true, factory) == 0, "Pending privileged spawns must respect CPU process cap");
            var first = manager.runTick(config.fuelPerTick(), config.quantum());
            require(first.fuelUsed() == 65_536 && first.turns() == 256, "CPU tick budget must be shared by all processes");
            require(consumed.size() == 16 && consumed.values().stream().allMatch(fuel -> fuel == 4096),
                    "All 16 runnable processes must share the tick fairly");
            require(manager.spawn(HEADER, false, factory) == 0, "Live processes must respect CPU process cap");
            var second = manager.runTick(config.fuelPerTick(), config.quantum());
            require(second.fuelUsed() == 65_536 && consumed.values().stream().mapToLong(Long::longValue).sum() == 131_072,
                    "CPU fuel budget must reset for each tick");
            manager.queueKill(1);
            manager.runTick(1, config.quantum());
            require(manager.spawn(HEADER, false, factory) != 0, "Terminating a process must free CPU process capacity");
            require(manager.spawn(HEADER, true, factory) == 0, "Mixed live and pending processes must respect CPU process cap");
        }
    }
    private static void deviceIdentityAndDispatch() {
        UUID id = UUID.randomUUID();
        InterfaceId type = new InterfaceId(99, "checks:device", 1, 0);
        Device device = new Device() {
            @Override public UUID id() { return id; }
            @Override public InterfaceId interfaceId() { return type; }
            @Override public DeviceResult device_call(DeviceCall call) {
                return switch (call.operation()) { case 7 -> DeviceResult.ok(new byte[] {42}); default -> DeviceResult.error(-3); };
            }
            @Override public DeviceSnapshot snapshot() { return new DeviceSnapshot(1, id, "checks:device", Map.of()); }
        };
        DeviceBus first = new DeviceBus(), second = new DeviceBus();
        first.attach(device);
        require(first.enumerate().getFirst().interfaceId().equals(type), "Exactly one interface must be discoverable");
        require(first.device_call(id, new DeviceCall(7, new byte[0], 1)).response()[0] == 42, "Operation dispatch");
        second.attach(first.detach(id).orElseThrow());
        require(second.enumerate().getFirst().id().equals(id), "Moving a component must preserve identity");
        require(first.device_call(id, new DeviceCall(7, new byte[0], 1)).code() == -7, "Detached UUID must not remain callable");
        try { new DeviceCall(0, new byte[256 * 1024], 1); throw new AssertionError("Combined limit must apply"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void firmwareDeduplication() throws Exception {
        Path temporary = Files.createTempDirectory("openwac-firmware-check-");
        try {
            var repository = new FileFirmwareRepository(temporary, Map.of());
            byte[] bios = bios();
            String first = repository.install(bios), second = repository.install(bios);
            require(first.equals(second), "Identical firmware must share a content reference");
            try (var files = Files.list(temporary)) { require(files.count() == 1, "Identical firmware must occupy one blob"); }
            var a = new BiosEeprom(UUID.randomUUID(), first, repository);
            var b = new BiosEeprom(UUID.randomUUID(), second, repository);
            require(!a.id().equals(b.id()) && Arrays.equals(a.bootloader(), b.bootloader()), "Physical EEPROM identity is independent of shared code");
            byte[] loaded = repository.load(first); loaded[0] = 1;
            require(repository.load(first)[0] == 0, "Consumers must not mutate the shared stored image");
        } finally {
            try (var files = Files.list(temporary)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(temporary);
        }
    }
    private static byte[] bios() throws IOException {
        try (InputStream input = RuntimeChecks.class.getResourceAsStream("/data/openwac/firmware/bootstrap-v1.wasm")) {
            if (input == null) throw new FileNotFoundException("Bundled BIOS");
            return input.readAllBytes();
        }
    }
    private static void bootAndColdRestore() {
        String reference = FileFirmwareRepository.DEFAULT_BIOS;
        var repository = new FileFirmwareRepository(Path.of("unused-firmware-check-path"),
                Map.of(reference, () -> RuntimeChecks.class.getResourceAsStream("/data/openwac/firmware/bootstrap-v1.wasm")));
        var machine = new VirtualMachine(UUID.randomUUID(), new MachineConfig(64, 8, 2, 2 * 1024 * 1024));
        var engine = new ChicoryEngine();
        List<String> messages = new ArrayList<>();
        var memory = new MemoryBudget(machine.config().maxMemoryBytes());
        ProcessFactory factory = (id, wasm, privileged) -> engine.createProcess(wasm,
                new EngineLimits(1024 * 1024, 1024 * 1024, 8), memory, host(messages), privileged);
        machine.powerOn(); machine.runTick(factory);
        require(machine.state() == MachineState.CRASHED, "Missing BIOS must fail boot");
        UUID deviceId = UUID.randomUUID();
        machine.devices().attach(new BiosEeprom(deviceId, reference, repository));
        machine.setBootMedia(deviceId);
        machine.powerOn(); machine.runTick(factory);
        require(machine.state() == MachineState.RUNNING, "Installed BIOS must boot");
        require(messages.equals(List.of("OpenWAC BIOS ready.")), "Boot must execute EEPROM bytes");
        MachineSnapshot saved = machine.snapshot();
        machine.powerOff();
        require(machine.processes().size() == 0 && machine.state() == MachineState.OFF, "Power-off must release all process handles");

        var restored = new VirtualMachine(saved.machineId(), saved.config());
        var media = saved.devices().getFirst();
        restored.devices().attach(new BiosEeprom(media.id(), media.metadata().get("firmware"), repository));
        restored.setBootMedia(saved.bootMedia());
        try {
            restored.powerOn(); restored.runTick(factory);
            require(restored.state() == MachineState.RUNNING && restored.id().equals(machine.id()), "Cold restoration preserves identity and starts a fresh runtime");
            require(messages.equals(List.of("OpenWAC BIOS ready.", "OpenWAC BIOS ready.")), "Cold reload must start the saved BIOS once");
        } finally { restored.powerOff(); }
    }
    private static HostServices host(List<String> messages) {
        return host(new DeviceBus(), messages);
    }
    private static HostServices host(DeviceBus devices, List<String> messages) {
        return new HostServices(devices, new EventHub(4), text -> { messages.add(text); return true; },
                new HostServices.ProcessControl() {
                    @Override public long spawn(byte[] wasm, boolean privileged) { return 0; }
                    @Override public boolean kill(long id) { return false; }
                }, NO_METRICS);
    }
    private static void hostileDeclarationsAndPointers() {
        var engine = new ChicoryEngine();
        for (String hex : List.of(
                "0061736d010000000105ffffffff0f", // Oversized type vector.
                "0061736d01000000010401600000030201000a0701050181087f0b")) { // Compressed local count > 1024.
            try (var ignored = engine.compile(HexFormat.of().parseHex(hex), LIMITS)) { throw new AssertionError("Hostile declarations must be rejected before parsing"); }
            catch (IllegalArgumentException expected) {}
        }
        byte[] recursion = HexFormat.of().parseHex("0061736d0100000001040160000003020100070a01065f737461727400000a0601040010000b");
        try (WasmProcess process = engine.createProcess(recursion, LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(new ArrayList<>()), true)) {
            WasmProcess.Step step;
            int turns = 0;
            do { step = process.runQuantum(64); } while (step.reason() != WasmProcess.Reason.TRAPPED && ++turns < 20);
            require(step.reason() == WasmProcess.Reason.TRAPPED, "Recursive stack growth must be bounded across turns");
        }
        UUID id = UUID.randomUUID();
        int[] invocations = {0};
        DeviceBus bus = new DeviceBus();
        bus.attach(new Device() {
            @Override public UUID id() { return id; }
            @Override public InterfaceId interfaceId() { return new InterfaceId(99, "checks:device", 1, 0); }
            @Override public DeviceResult device_call(DeviceCall call) { invocations[0]++; return DeviceResult.ok(new byte[] {42}); }
            @Override public DeviceSnapshot snapshot() { return new DeviceSnapshot(1, id, "checks:device", Map.of()); }
        });
        try (WasmProcess process = engine.createProcess(deviceCaller(id, true), LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(bus, new ArrayList<>()), true)) {
            require(process.runQuantum(256).reason() == WasmProcess.Reason.EXITED, "Invalid pointer must produce the documented error");
            require(invocations[0] == 0, "Validate response pointers before invoking a device with side effects");
        }
        try (WasmProcess process = engine.createProcess(deviceCaller(id, false), LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(bus, new ArrayList<>()), true)) {
            require(process.runQuantum(256).reason() == WasmProcess.Reason.EXITED, "Valid UUID call must copy response bytes");
            require(invocations[0] == 1, "Dispatch exactly once");
        }
    }
    private static byte[] deviceCaller(UUID id, boolean badPointer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.writeBytes(HEADER);
        section(output, 1, new byte[] {2, 0x60, 6, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 1, 0x7f, 0x60, 0, 0});
        ByteArrayOutputStream imports = new ByteArrayOutputStream(); imports.write(1); name(imports, "hal"); name(imports, "hal_device_call"); imports.write(0); imports.write(0);
        section(output, 2, imports.toByteArray());
        section(output, 3, new byte[] {1, 1}); section(output, 5, new byte[] {1, 1, 1, 1});
        ByteArrayOutputStream exports = new ByteArrayOutputStream(); exports.write(2); name(exports, "_start"); exports.write(0); exports.write(1); name(exports, "memory"); exports.write(2); exports.write(0);
        section(output, 7, exports.toByteArray());
        ByteArrayOutputStream code = new ByteArrayOutputStream(); code.write(0); // No locals.
        for (int value : new int[] {0, 7, 16, 0, badPointer ? 65535 : 16, badPointer ? 2 : 1}) constant(code, value);
        code.writeBytes(new byte[] {0x10, 0}); // Call imported device function.
        constant(code, badPointer ? -8 : 0);
        code.writeBytes(new byte[] {0x47, 0x04, 0x40, 0, 0x0b}); // Trap if return code differs.
        if (!badPointer) {
            constant(code, 16); code.writeBytes(new byte[] {0x2d, 0, 0}); constant(code, 42);
            code.writeBytes(new byte[] {0x47, 0x04, 0x40, 0, 0x0b}); // Response byte must be 42.
        }
        code.write(0x0b);
        ByteArrayOutputStream bodies = new ByteArrayOutputStream(); bodies.write(1); uleb(bodies, code.size()); bodies.writeBytes(code.toByteArray());
        section(output, 10, bodies.toByteArray());
        ByteArrayOutputStream data = new ByteArrayOutputStream(); data.writeBytes(new byte[] {1, 0, 0x41, 0, 0x0b, 16});
        data.writeBytes(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
        section(output, 11, data.toByteArray());
        return output.toByteArray();
    }
    private static void section(ByteArrayOutputStream out, int id, byte[] bytes) { out.write(id); uleb(out, bytes.length); out.writeBytes(bytes); }
    private static void name(ByteArrayOutputStream out, String name) { byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8); uleb(out, bytes.length); out.writeBytes(bytes); }
    private static void uleb(ByteArrayOutputStream out, int value) {
        do { int b = value & 127; value >>>= 7; out.write(b | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    private static void constant(ByteArrayOutputStream out, int value) {
        out.write(0x41);
        while (true) {
            int b = value & 127; value >>= 7;
            boolean done = (value == 0 && (b & 64) == 0) || (value == -1 && (b & 64) != 0);
            out.write(b | (done ? 0 : 128)); if (done) return;
        }
    }
    private static void continuationsAndPreemption() throws Exception {
        var engine = new ChicoryEngine();
        List<String> messages = new ArrayList<>();
        boolean yielded = false, preempted = false;
        try (WasmProcess process = engine.createProcess(bios(), LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(messages), true)) {
            for (int i = 0; i < 40; i++) {
                var step = process.runQuantum(3);
                require(step.reason() != WasmProcess.Reason.TRAPPED && step.reason() != WasmProcess.Reason.EXITED,
                        "BIOS unexpectedly stopped: " + step.diagnostic());
                require(step.fuelUsed() <= 3, "Instruction and host work must respect quantum");
                yielded |= step.reason() == WasmProcess.Reason.YIELDED;
                preempted |= step.reason() == WasmProcess.Reason.QUANTUM_EXHAUSTED;
            }
        }
        require(yielded && preempted, "Both suspension paths must be exercised");
        require(messages.equals(List.of("OpenWAC BIOS ready.")), "Resuming must preserve the continuation rather than restart _start: " + messages);
        try (WasmProcess process = engine.createProcess(bios(), LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(new ArrayList<>()), false)) {
            require(process.runQuantum(256).reason() == WasmProcess.Reason.TRAPPED, "Unprivileged modules cannot link chat imports");
        }
        byte[] endless = HexFormat.of().parseHex("0061736d0100000001040160000003020100070a01065f737461727400000a0901070003400c000b0b");
        try (WasmProcess process = engine.createProcess(endless, LIMITS, new MemoryBudget(LIMITS.maxMemoryBytes()), host(new ArrayList<>()), true)) {
            for (int i = 0; i < 10; i++) {
                var step = process.runQuantum(17);
                require(step.reason() == WasmProcess.Reason.QUANTUM_EXHAUSTED && step.fuelUsed() == 17,
                        "An infinite loop without host calls must be preempted: " + step);
            }
        }
    }
}
