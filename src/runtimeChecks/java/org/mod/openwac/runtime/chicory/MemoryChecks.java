package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.wasm.types.MemoryLimits;
import org.mod.openwac.core.device.DeviceBus;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.machine.*;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.runtime.hal.HostServices;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Checks real WASM growth, shared allocation, and cleanup without Minecraft startup. */
public final class MemoryChecks {
    private static final int PAGE = MemoryBudget.PAGE_BYTES;
    private static final byte[] YIELD = {0x10, 0};

    public static void run() throws Exception {
        for (int modules = 1; modules <= 4; modules++) {
            var config = new MachineConfig(65_536, 256, 16, modules * 1024L * 1024);
            require(new MemoryBudget(config.maxMemoryBytes()).capacityPages() == modules * 16, "RAM capacity must add in pages");
        }
        try { new MachineConfig(65_536, 256, 16, 0); throw new AssertionError("Zero RAM must fail"); }
        catch (IllegalArgumentException expected) { }
        sharedGrowth();
        initializationAndCleanup();
        serverLimitAndRollback();
    }

    private static void sharedGrowth() {
        var budget = new MemoryBudget(3L * PAGE);
        var independent = new MemoryBudget(3L * PAGE);
        // First process owns one page, then requests a second page after another process exits.
        try (var first = process(module(1, 3, concat(YIELD, grow(1, 1), YIELD)), budget);
             var second = process(module(1, 3, concat(grow(1, 1), grow(1, -1), grow(0, 2), YIELD)), budget);
             var otherComputer = process(module(3, 3, YIELD), independent)) {
            expect(first, WasmProcess.Reason.YIELDED);
            require(budget.usedPages() == 1, "Only initial pages must be reserved");
            expect(second, WasmProcess.Reason.YIELDED);
            require(budget.usedPages() == 3, "Processes must fill a shared pool and reject extra growth");
            expect(otherComputer, WasmProcess.Reason.YIELDED);
            require(independent.usedPages() == 3, "Computer budgets must be independent");
            expect(second, WasmProcess.Reason.EXITED);
            require(budget.usedPages() == 1, "Exit must reclaim memory before publishing completion");
            expect(first, WasmProcess.Reason.YIELDED);
            require(budget.usedPages() == 2, "A surviving process must reuse reclaimed pages");
            expect(first, WasmProcess.Reason.EXITED);
            expect(otherComputer, WasmProcess.Reason.EXITED);
            require(budget.usedPages() == 0 && independent.usedPages() == 0, "All pages must be reclaimed");
        }
        try (var bounded = process(module(1, 1, concat(grow(1, -1), grow(-1, -1), grow(0, 1))), budget)) {
            expect(bounded, WasmProcess.Reason.EXITED);
        }
        try (var zeroInitial = process(module(0, 3, concat(grow(3, 0), grow(0, 3), YIELD)), budget)) {
            expect(zeroInitial, WasmProcess.Reason.YIELDED);
            require(budget.usedPages() == 3, "Zero-initial memory must be able to grow to the full computer capacity");
            expect(zeroInitial, WasmProcess.Reason.EXITED);
        }
    }

    private static void initializationAndCleanup() throws Exception {
        var budget = new MemoryBudget(2L * PAGE);
        try (var holding = process(module(2, 2, YIELD), budget)) {
            expect(holding, WasmProcess.Reason.YIELDED);
            try (var denied = process(module(1, 2, YIELD), budget)) {
                expect(denied, WasmProcess.Reason.TRAPPED);
                require(budget.usedPages() == 2, "Initial allocation failure must not leak pages");
            }
            holding.close();
            awaitEmpty(budget);
        }
        // Missing _start fails after instance memory has been constructed.
        byte[] badExport = module(2, 2, new byte[0]);
        byte[] startName = "_start".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= badExport.length - startName.length; i++) {
            if (java.util.Arrays.equals(java.util.Arrays.copyOfRange(badExport, i, i + startName.length), startName)) {
                badExport[i] = 'x';
                break;
            }
        }
        try (var bad = process(badExport, budget)) { expect(bad, WasmProcess.Reason.TRAPPED); }
        require(budget.usedPages() == 0, "Initialization failure after allocation must release pages");
        try (var trapped = process(module(1, 2, concat(grow(1, 1), new byte[] {0})), budget)) {
            expect(trapped, WasmProcess.Reason.TRAPPED);
            require(budget.usedPages() == 0, "Trap must release initial and grown memory");
        }
        try (var cancelledBeforeStart = process(module(2, 2, YIELD), budget)) {
            cancelledBeforeStart.close();
        }
        awaitEmpty(budget);
        try (var reused = process(module(2, 2, new byte[0]), budget)) { expect(reused, WasmProcess.Reason.EXITED); }
    }

    private static void serverLimitAndRollback() {
        var server = new MemoryBudget(3L * PAGE);
        var firstComputer = new MemoryBudget(3L * PAGE);
        var secondComputer = new MemoryBudget(3L * PAGE);
        try (var first = new BudgetedMemory(new MemoryLimits(2, 3), firstComputer, server);
             var second = new BudgetedMemory(new MemoryLimits(1, 3), secondComputer, server)) {
            require(second.grow(1) == -1, "Server cap must reject growth across computers");
            require(secondComputer.usedPages() == 1 && second.pages() == 1, "Denied growth must roll back computer reservation");
            try {
                new BudgetedMemory(new MemoryLimits(1, 3), secondComputer, server);
                throw new AssertionError("Server cap must reject initial allocation");
            } catch (IllegalStateException expected) { }
            require(secondComputer.usedPages() == 1, "Denied initialization must roll back computer reservation");
            first.close();
            first.close();
            require(second.grow(2) == 1, "Freed server pages must be reusable without double release");
        }
        require(server.usedPages() == 0 && firstComputer.usedPages() == 0 && secondComputer.usedPages() == 0,
                "Closing memories must balance every reservation");
    }

    private static WasmProcess process(byte[] wasm, MemoryBudget budget) {
        var host = new HostServices(new DeviceBus(), new EventHub(4), text -> true, new HostServices.ProcessControl() {
            @Override public long spawn(byte[] bytes, boolean privileged) { return 0; }
            @Override public boolean kill(long id) { return false; }
        }, new HostServices.MachineMetrics() {
            @Override public long cpuTimeNanos() { return 0; }
            @Override public long ramUsedPages() { return budget.usedPages(); }
        });
        return new ChicoryEngine().createProcess(wasm, new EngineLimits(1024 * 1024,
                (long) budget.capacityPages() * PAGE, 256), budget, host, true);
    }

    private static void expect(WasmProcess process, WasmProcess.Reason reason) {
        WasmProcess.Step step = process.runQuantum(256);
        require(step.reason() == reason, "Expected " + reason + ", got " + step);
    }

    private static void awaitEmpty(MemoryBudget budget) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (budget.usedPages() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        require(budget.usedPages() == 0, "Cancellation must eventually release memory after execution stops");
    }

    private static byte[] grow(int pages, int expected) {
        var code = new ByteArrayOutputStream();
        constant(code, pages); code.writeBytes(new byte[] {0x40, 0});
        constant(code, expected); code.writeBytes(new byte[] {0x47, 0x04, 0x40, 0, 0x0b});
        return code.toByteArray();
    }

    private static byte[] module(int initial, int maximum, byte[] instructions) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 97, 115, 109, 1, 0, 0, 0});
        section(out, 1, new byte[] {1, 0x60, 0, 0});
        var imports = new ByteArrayOutputStream();
        imports.write(1); name(imports, "hal"); name(imports, "hal_yield"); imports.writeBytes(new byte[] {0, 0});
        section(out, 2, imports.toByteArray()); section(out, 3, new byte[] {1, 0});
        var memory = new ByteArrayOutputStream(); memory.writeBytes(new byte[] {1, 1});
        uleb(memory, initial); uleb(memory, maximum); section(out, 5, memory.toByteArray());
        var exports = new ByteArrayOutputStream(); exports.write(1); name(exports, "_start"); exports.writeBytes(new byte[] {0, 1});
        section(out, 7, exports.toByteArray());
        var body = new ByteArrayOutputStream(); body.write(0); body.writeBytes(instructions); body.write(0x0b);
        var code = new ByteArrayOutputStream(); code.write(1); uleb(code, body.size()); code.writeBytes(body.toByteArray());
        section(out, 10, code.toByteArray());
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream(); for (byte[] part : parts) out.writeBytes(part); return out.toByteArray();
    }
    private static void section(ByteArrayOutputStream out, int id, byte[] data) { out.write(id); uleb(out, data.length); out.writeBytes(data); }
    private static void name(ByteArrayOutputStream out, String name) {
        byte[] data = name.getBytes(StandardCharsets.UTF_8); uleb(out, data.length); out.writeBytes(data);
    }
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
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
