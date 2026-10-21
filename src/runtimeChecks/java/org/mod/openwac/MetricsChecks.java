package org.mod.openwac;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.core.machine.*;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.*;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import org.mod.openwac.service.ComputerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Exercises metric imports through real WASM and the asynchronous runtime controller. */
final class MetricsChecks {
    private static final int PAGE = MemoryBudget.PAGE_BYTES;
    private static final byte[] YIELD = {0x10, 1};

    static void run() throws Exception {
        for (String name : List.of("hal_cpu_time_ns", "hal_ram_used_pages")) {
            var reads = new AtomicInteger();
            long value = 0x1_2345_6789L;
            var metrics = metrics(() -> { reads.incrementAndGet(); return value; }, () -> { reads.incrementAndGet(); return value; });
            byte[] wasm = module(name, 0, 1, check(value));
            var budget = new MemoryBudget(PAGE);
            try (var process = process(wasm, budget, metrics, true)) {
                require(process.runQuantum(1).reason() == WasmProcess.Reason.QUANTUM_EXHAUSTED && reads.get() == 0,
                        "Metric must charge one unit beyond the call instruction before sampling");
                require(process.runQuantum(1).reason() == WasmProcess.Reason.QUANTUM_EXHAUSTED && reads.get() == 1,
                        "Metric must sample exactly once after paying its host charge");
                expect(process, WasmProcess.Reason.EXITED);
            }
            try (var process = process(wasm, budget, metrics, false)) { expect(process, WasmProcess.Reason.TRAPPED); }
            require(reads.get() == 1, "Unprivileged linking must fail without reading metrics");
        }
        sharedRamReadings();
        completedTickTiming();
    }

    private static void sharedRamReadings() throws Exception {
        var budget = new MemoryBudget(3L * PAGE);
        var metrics = metrics(() -> 0, budget::usedPages);
        byte[] firstWasm = module("hal_ram_used_pages", 1, 3,
                concat(check(1), YIELD, check(2), grow(1), check(3), YIELD,
                        grow(1), check(3), YIELD, check(2), YIELD));
        try (var first = process(firstWasm, budget, metrics, true);
             var second = process(module("hal_ram_used_pages", 1, 3, concat(check(2), YIELD)), budget, metrics, true)) {
            expect(first, WasmProcess.Reason.YIELDED);
            expect(second, WasmProcess.Reason.YIELDED);
            expect(first, WasmProcess.Reason.YIELDED);
            expect(first, WasmProcess.Reason.YIELDED);
            expect(second, WasmProcess.Reason.EXITED);
            expect(first, WasmProcess.Reason.YIELDED);
            expect(first, WasmProcess.Reason.EXITED);
        }
        try (var observer = process(module("hal_ram_used_pages", 0, 3, check(0)), budget, metrics, true)) {
            expect(observer, WasmProcess.Reason.EXITED);
        }
    }

    private static void completedTickTiming() throws Exception {
        byte[] finite = module("hal_cpu_time_ns", 0, 1, check(0));
        // Check both an early-exit tick and a tick that exhausts fuel while remaining runnable.
        byte[] endless = module("hal_cpu_time_ns", 0, 1, concat(check(0), new byte[] {0x03, 0x40, 0x0c, 0, 0x0b}));
        for (byte[] wasm : List.of(finite, endless)) {
            var repository = new FirmwareRepository() {
                @Override public byte[] load(String reference) { return wasm.clone(); }
                @Override public String install(byte[] bytes) { throw new UnsupportedOperationException(); }
            };
            var bios = new BiosEeprom(UUID.randomUUID(), "metrics-check", repository);
            try (var runtime = new ComputerRuntime(UUID.randomUUID(), List.of(bios), new MachineConfig(64, 8, 16, PAGE))) {
                require(runtime.cpuTimeNanos() == 0 && runtime.ramUsedPages() == 0, "A fresh computer must have zero metrics");
                runtime.tick();
                await(() -> runtime.cpuTimeNanos() > 0, "Completed execution must publish elapsed time");
                require(runtime.state() != MachineState.CRASHED, "Guest must read zero timing during first execution tick: " + runtime.diagnostic());
                require(runtime.state() == (wasm == finite ? MachineState.OFF : MachineState.RUNNING), "Tick must finish in expected state");
                long completed = runtime.cpuTimeNanos();
                require(runtime.cpuTimeNanos() == completed, "Timing must stay stable between execution ticks");
                if (wasm == finite) {
                    runtime.tick();
                    require(runtime.cpuTimeNanos() == completed, "Skipped ticks must retain the previous duration");
                }
            }
        }
        try (var runtime = new ComputerRuntime(UUID.randomUUID(), List.of(), new MachineConfig(64, 8, 16, PAGE))) {
            runtime.tick();
            await(() -> runtime.cpuTimeNanos() > 0, "Faulted boot ticks must also publish timing");
            require(runtime.state() == MachineState.CRASHED, "Missing BIOS must fail boot");
        }
    }

    private static HostServices.MachineMetrics metrics(LongSupplier cpu, LongSupplier ram) {
        return new HostServices.MachineMetrics() {
            @Override public long cpuTimeNanos() { return cpu.getAsLong(); }
            @Override public long ramUsedPages() { return ram.getAsLong(); }
        };
    }
    private static WasmProcess process(byte[] wasm, MemoryBudget budget, HostServices.MachineMetrics metrics, boolean privileged) {
        var host = new HostServices(new DeviceBus(), new EventHub(4), text -> true, new HostServices.ProcessControl() {
            @Override public long spawn(byte[] bytes, boolean childPrivileged) { return 0; }
            @Override public boolean kill(long id) { return false; }
        }, metrics);
        return new ChicoryEngine().createProcess(wasm, new EngineLimits(1024 * 1024,
                (long) budget.capacityPages() * PAGE, 256), budget, host, privileged);
    }
    private static void expect(WasmProcess process, WasmProcess.Reason reason) {
        var step = process.runQuantum(256);
        require(step.reason() == reason, "Expected " + reason + ", got " + step);
    }
    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        require(condition.getAsBoolean(), message);
    }
    private static byte[] check(long expected) {
        var code = new ByteArrayOutputStream();
        code.writeBytes(new byte[] {0x10, 0, 0x42}); signed(code, expected);
        code.writeBytes(new byte[] {0x52, 0x04, 0x40, 0, 0x0b}); // Trap if metric differs.
        return code.toByteArray();
    }
    private static byte[] grow(int pages) {
        var code = new ByteArrayOutputStream(); code.write(0x41); signed(code, pages);
        code.writeBytes(new byte[] {0x40, 0, 0x1a}); return code.toByteArray();
    }
    private static byte[] module(String metric, int initial, int maximum, byte[] instructions) {
        var out = new ByteArrayOutputStream(); out.writeBytes(new byte[] {0, 97, 115, 109, 1, 0, 0, 0});
        section(out, 1, new byte[] {2, 0x60, 0, 1, 0x7e, 0x60, 0, 0});
        var imports = new ByteArrayOutputStream(); imports.write(2);
        name(imports, "hal"); name(imports, metric); imports.writeBytes(new byte[] {0, 0});
        name(imports, "hal"); name(imports, "hal_yield"); imports.writeBytes(new byte[] {0, 1});
        section(out, 2, imports.toByteArray()); section(out, 3, new byte[] {1, 1});
        var memory = new ByteArrayOutputStream(); memory.writeBytes(new byte[] {1, 1});
        uleb(memory, initial); uleb(memory, maximum); section(out, 5, memory.toByteArray());
        var exports = new ByteArrayOutputStream(); exports.write(1); name(exports, "_start"); exports.writeBytes(new byte[] {0, 2});
        section(out, 7, exports.toByteArray());
        var body = new ByteArrayOutputStream(); body.write(0); body.writeBytes(instructions); body.write(0x0b);
        var code = new ByteArrayOutputStream(); code.write(1); uleb(code, body.size()); code.writeBytes(body.toByteArray());
        section(out, 10, code.toByteArray()); return out.toByteArray();
    }
    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream(); for (byte[] part : parts) out.writeBytes(part); return out.toByteArray();
    }
    private static void name(ByteArrayOutputStream out, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8); uleb(out, bytes.length); out.writeBytes(bytes);
    }
    private static void section(ByteArrayOutputStream out, int id, byte[] bytes) { out.write(id); uleb(out, bytes.length); out.writeBytes(bytes); }
    private static void uleb(ByteArrayOutputStream out, int value) {
        do { int b = value & 127; value >>>= 7; out.write(b | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    private static void signed(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) value & 127; value >>= 7;
            boolean done = (value == 0 && (b & 64) == 0) || (value == -1 && (b & 64) != 0);
            out.write(b | (done ? 0 : 128)); if (done) return;
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
