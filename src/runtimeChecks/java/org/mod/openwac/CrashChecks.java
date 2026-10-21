package org.mod.openwac;

import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.core.machine.*;
import org.mod.openwac.core.process.*;
import org.mod.openwac.core.device.DeviceBus;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.runtime.*;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import org.mod.openwac.service.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Whole-computer reporting without a running Minecraft server or real boot media. */
final class CrashChecks {
    private static final MachineConfig CONFIG = new MachineConfig(64, 8, 16, 1024 * 1024);
    private static final byte[] HEADER = {0,97,115,109,1,0,0,0};
    static void run() throws Exception {
        linkingFailure(); quietPrograms(); creationFailure(); watchdog(); formattingAndDeduplication();
    }
    private static void linkingFailure() throws Exception {
        // No implementation is registered for this imported function.
        byte[] wasm = missingImport();
        try (var runtime = new ComputerRuntime(UUID.randomUUID(), List.of(bios(wasm)), CONFIG)) {
            runtime.tick(); await(() -> runtime.state() == MachineState.CRASHED, "Boot import failure must crash computer");
            var report = runtime.crashReport();
            require(report != null, "Crash report must be published before CRASHED is visible");
            require(report.crash().processId() == 1 && report.crash().firmware().equals("checks:broken-bios"), "Report must identify BIOS and process");
            var details = report.crash().failure();
            require(details.stage().equals("Module linking/instantiation"), "Link failures must retain their execution stage");
            require(details.message().contains("UnlinkableException") && details.stackTrace().contains("missing_boot_import"),
                    "Original linker message and missing import must survive runtime handoff: " + details);
            String rendered = report.render("minecraft:overworld", "1, 2, 3");
            require(rendered.contains("missing_boot_import") && rendered.contains("Fuel per tick: 64")
                    && rendered.contains("RAM pages (allocated/installed)") && rendered.contains("Process limit: 16"), "Full report must contain context and trace");
            var entries = new ArrayList<String>(); var publisher = new CrashReportPublisher(entries::add);
            require(publisher.publish(report,"world","position"), "First crash must print");
            runtime.tick();
            require(!publisher.publish(runtime.crashReport(),"world","position") && entries.size() == 1, "Subsequent ticks must not repeat a report");
        }
        try (var runtime = new ComputerRuntime(UUID.randomUUID(), List.of(), CONFIG)) {
            runtime.tick(); await(() -> runtime.state() == MachineState.CRASHED, "Missing BIOS must fail startup");
            require(runtime.crashReport() != null && runtime.crashReport().crash().failure().stackTrace().contains("No BIOS EEPROM"),
                    "Fatal boot exceptions must retain their original cause");
        }
    }
    private static void quietPrograms() {
        var machine = new VirtualMachine(UUID.randomUUID(), CONFIG);
        machine.devices().attach(bios(HEADER));
        var reports = new ArrayList<MachineCrash>(); machine.onCrash(reports::add);
        ProcessFactory factory = (id, wasm, privileged) -> new WasmProcess() {
            private boolean spawned;
            @Override public Step runQuantum(long fuel) {
                if (id != 1) throw new IllegalArgumentException("child failed");
                if (!spawned) {
                    spawned = true;
                    machine.processes().spawn(HEADER, false, (child, bytes, tier) -> new WasmProcess() {
                        @Override public Step runQuantum(long grant) { throw new IllegalArgumentException("child failed"); }
                        @Override public void wake() {}
                        @Override public void close() {}
                    });
                }
                return new Step(fuel, Reason.QUANTUM_EXHAUSTED, null);
            }
            @Override public void wake() {}
            @Override public void close() {}
        };
        machine.powerOn(); machine.runTick(factory);
        require(machine.state() == MachineState.RUNNING && machine.crashDetails() == null && reports.isEmpty(), "Child failure must not produce a crash report");
        machine.kernelPanic("Recoverable kernel panic"); machine.runTick(factory);
        require(machine.state() == MachineState.RUNNING && reports.isEmpty(), "Nonfatal kernel panic must stay quiet");
        machine.powerOff(); require(reports.isEmpty(), "Normal shutdown must not report a crash");
    }
    private static void creationFailure() {
        var machine = new VirtualMachine(UUID.randomUUID(), CONFIG);
        machine.devices().attach(bios(HEADER)); machine.powerOn();
        machine.runTick((id, wasm, privileged) -> { throw new IllegalArgumentException("Factory failed", new IOException("nested cause")); });
        require(machine.state() == MachineState.CRASHED, "Failed BIOS creation must crash computer");
        var details = machine.crashDetails().failure();
        require(details.stage().equals("Process creation") && details.stackTrace().contains("nested cause") && details.stackTrace().contains("Caused by"),
                "Factory failures must preserve their cause chain");
    }
    private static void watchdog() throws Exception {
        byte[] wasm;
        try (var stream = CrashChecks.class.getResourceAsStream("/data/openwac/firmware/bootstrap-v1.wasm")) { wasm = stream.readAllBytes(); }
        var machine = new VirtualMachine(UUID.randomUUID(), new MachineConfig(256,256,16,1024*1024));
        machine.devices().attach(bios(wasm));
        var budget = new MemoryBudget(1024*1024);
        var blocked = new CountDownLatch(1);
        var host = new HostServices(new DeviceBus(), new EventHub(4), text -> {
            try { blocked.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            return true;
        }, new HostServices.ProcessControl() {
            @Override public long spawn(byte[] bytes, boolean privileged) { return 0; }
            @Override public boolean kill(long id) { return false; }
        }, RuntimeChecks.NO_METRICS);
        machine.powerOn();
        try {
            machine.runTick((id, bytes, privileged) -> new ChicoryEngine().createProcess(bytes,
                    new EngineLimits(1024*1024,1024*1024,256),budget,host,privileged));
            require(machine.state() == MachineState.CRASHED && machine.crashDetails().failure().stage().equals("Watchdog timeout"),
                    "Watchdog timeout must reach the whole-computer crash report");
            require(machine.crashDetails().failure().stackTrace().contains("TimeoutException"), "Timeout report must retain its trace");
        } finally { blocked.countDown(); machine.powerOff(); }
        await(() -> budget.usedPages() == 0, "Watchdog cancellation must still clean up memory");
    }
    private static void formattingAndDeduplication() {
        var cause = new IllegalStateException("startup failed", new IOException("disk unavailable"));
        var details = FailureDetails.capture("Computer startup", cause);
        var crash = new MachineCrash(UUID.randomUUID(),0,0,"checks:startup",null,details);
        var kernel = FailureDetails.capture("Kernel initialization", new IllegalArgumentException("bad descriptor"));
        var report = new ComputerCrashReport(crash,0,16,kernel);
        var entries = new ArrayList<String>(); var publisher = new CrashReportPublisher(entries::add);
        require(!publisher.publish(null,"world","position"), "No crash must remain quiet");
        require(publisher.publish(report,"world","position") && !publisher.publish(report,"world","position"), "Reports must be deduplicated");
        publisher.reset(); require(publisher.publish(report,"world","position") && entries.size() == 2, "A new power attempt may report again");
        require(entries.get(0).contains("disk unavailable") && entries.get(0).contains("bad descriptor"), "Startup and kernel context must survive formatting");
        var huge = FailureDetails.capture("stage",new IllegalStateException("x".repeat(100_000)));
        require(huge.stackTrace().length() <= FailureDetails.TRACE_LIMIT && huge.stackTrace().contains("[truncated]") && huge.summary().length() <= 512,
                "Unbounded exception text must be truncated explicitly");
    }
    private static BiosEeprom bios(byte[] wasm) {
        return new BiosEeprom(UUID.randomUUID(),"checks:broken-bios",new FirmwareRepository() {
            @Override public byte[] load(String reference) { return wasm.clone(); }
            @Override public String install(byte[] bytes) { throw new UnsupportedOperationException(); }
        });
    }
    private static byte[] missingImport() throws IOException {
        var out = new ByteArrayOutputStream(); out.write(HEADER);
        section(out,1,new byte[] {1,0x60,0,0});
        var imports=new ByteArrayOutputStream(); imports.write(1); name(imports,"hal"); name(imports,"missing_boot_import"); imports.write(new byte[] {0,0});
        section(out,2,imports.toByteArray()); section(out,3,new byte[] {1,0});
        var exports=new ByteArrayOutputStream(); exports.write(1); name(exports,"_start"); exports.write(new byte[] {0,1});
        section(out,7,exports.toByteArray()); section(out,10,new byte[] {1,2,0,0x0b}); return out.toByteArray();
    }
    private static void section(ByteArrayOutputStream out,int id,byte[]bytes) { out.write(id);out.write(bytes.length);out.writeBytes(bytes); }
    private static void name(ByteArrayOutputStream out,String name) { byte[]bytes=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.write(bytes.length);out.writeBytes(bytes); }
    private static void await(BooleanSupplier condition,String message)throws Exception { long deadline=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);require(condition.getAsBoolean(),message); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
