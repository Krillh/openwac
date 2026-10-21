package org.mod.openwac.runtime.chicory;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.machine.MemoryBudget;
import org.mod.openwac.core.process.*;
import org.mod.openwac.runtime.*;
import org.mod.openwac.runtime.hal.HostServices;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Synthetic kernel ABI fixtures exercise real metered interpreter continuations. */
public final class KernelChecks {
    private static final int I32 = 0x7f, I64 = 0x7e;
    private static final String INSTALL = "hal_install_kernel";
    public static void run() throws Exception {
        installationAndCalls(); blockingAndWake(); contentionAndKills(); exitReasons(); panicAndHooks(); invalidInstallations(); bootloader();
    }
    public static void reportChecks() throws Exception {
        for (int mode : new int[] {1,2,3,8,9,10,12,13,14}) {
            try (Fixture f = new Fixture()) {
                byte[] module = kernel(mode);
                long pid = f.spawn(installer(module,-16,new Code()),true);
                f.until(() -> f.manager.size()==0); f.noFailures();
                var reports=f.reports.drain();
                require(reports.size()==1, "Rejected kernel must emit one report, mode="+mode);
                var report=reports.get(0);
                require(report.processId()==pid && report.moduleBytes()==module.length && report.sha256().length()==64,
                        "Report must identify the installer and exact kernel");
                String message=report.details().message();
                if(mode==1 || mode==8) require(message.contains("pointer=") && message.contains("memory bytes="), "Range error must include exact bounds");
                if(mode==2 || mode==9) require(message.contains("expected") && message.contains("actual"), "Wrong signatures must identify expected and actual types");
                if(mode==12) require(message.contains("kernel_syscall") && message.contains("Missing required"), "Missing export must be named");
                if(mode==13) require(report.details().stackTrace().contains("missing_kernel_import"), "Missing import must survive linking failure");
                if(mode==14) require(message.contains("17") && message.contains("16") && message.contains("pages"), "Initial memory error must report requested and allowed pages");
                require(!report.details().stackTrace().isEmpty(), "Every rejection must retain its stack trace");
            }
        }
        try(Fixture f=new Fixture()) {
            byte[] invalid={1,2,3,4,5,6,7,8};
            f.spawn(installer(invalid,-16,new Code().op(0x03,0x40,0x0c,0,0x0b)),true);
            f.until(() -> f.computer.kernelFailure()!=null); f.noFailures();
            require(f.manager.size()==1 && f.reports.drain().size()==1, "Handled install failures must report while computer keeps running");
            f.spawn(installer(invalid,-16,new Code()),true);
            f.until(() -> f.manager.size()==1); f.noFailures();
            require(f.reports.drain().isEmpty(), "Repeated identical retries must be suppressed after draining");
        }
        try(Fixture f=new Fixture(65536)) {
            f.spawn(installer(kernel(0),-16,new Code()),true);
            f.until(() -> f.manager.size()==0); f.noFailures();
            String reason=f.reports.drain().get(0).details().message();
            require(reason.contains("requested 1") && reason.contains("computer available 0"), "Insufficient RAM must report requested and available pages");
        }
        try(Fixture f=new Fixture()) {
            byte[] valid=kernel(0);
            f.spawn(installer(valid,0,new Code().i(1024).i(valid.length).call(0).eq(-15)),true);
            f.until(() -> f.manager.size()==0 && !f.computer.hasWork()); f.noFailures();
            require(f.reports.drain().isEmpty(), "Successful and duplicate installations must stay quiet");
        }
        try(Fixture programFailure=new Fixture()) {
            programFailure.spawn(caller(new Code().op(0)),true);
            programFailure.until(() -> programFailure.manager.size()==0);
            require(programFailure.failures.size()==1 && programFailure.reports.drain().isEmpty(), "Ordinary program traps must not produce kernel rejection warnings");
        }
        Fixture cancelled=new Fixture();
        cancelled.spawn(installer(kernel(0),0,new Code()),true); cancelled.tick(); cancelled.close();
        require(cancelled.reports.drain().isEmpty(), "Shutdown cancellation must not report an invalid kernel");
        reportQueuePolicy(); runtimeReportDelivery();
    }
    private static void reportQueuePolicy() {
        var queue=new org.mod.openwac.service.KernelInstallReports();
        var details=org.mod.openwac.core.diagnostic.FailureDetails.capture("Kernel compilation",new IllegalArgumentException("invalid module"));
        var initial=KernelInstallFailure.capture(1,new byte[]{1},details);
        queue.offer(initial); queue.offer(KernelInstallFailure.capture(2,new byte[]{1},details));
        require(queue.drain().size()==1, "Identical errors from another installer must remain suppressed");
        queue.offer(initial); require(queue.drain().isEmpty(), "Suppression must survive queue drains");
        for(int i=2;i<=30;i++) queue.offer(KernelInstallFailure.capture(i,new byte[]{(byte)i},details));
        require(queue.drain().size()==15, "Each power attempt may report at most sixteen distinct failures");
        var nextAttempt=new org.mod.openwac.service.KernelInstallReports(); nextAttempt.offer(initial);
        require(nextAttempt.drain().size()==1, "Fresh runtime must reset suppression");
    }
    private static void runtimeReportDelivery() throws Exception {
        for(boolean keepRunning:new boolean[]{false,true}) {
            byte[] wasm=installer(kernel(3),-16,keepRunning?new Code().op(0x03,0x40,0x0c,0,0x0b):new Code());
            var firmware=new org.mod.openwac.core.firmware.FirmwareRepository() {
                @Override public byte[] load(String ref){return wasm.clone();}
                @Override public String install(byte[]bytes){throw new UnsupportedOperationException();}
            };
            var bios=new org.mod.openwac.core.device.eeprom.BiosEeprom(UUID.randomUUID(),"checks:kernel-report",firmware);
            try(var runtime=new org.mod.openwac.service.ComputerRuntime(UUID.randomUUID(),List.of(bios),
                    new org.mod.openwac.core.machine.MachineConfig(64,8,16,1024*1024))) {
                List<KernelInstallFailure> reports=new ArrayList<>();
                long deadline=System.nanoTime()+5_000_000_000L;
                while(reports.isEmpty() && System.nanoTime()<deadline){runtime.tick();reports.addAll(runtime.drainKernelInstallFailures());Thread.sleep(1);}
                require(reports.size()==1 && runtime.crashReport()==null, "Nonfatal failure must reach runtime delivery without a crash report");
                require(reports.get(0).details().stage().equals("Kernel initialization"), "Runtime event must preserve stage");
                if(!keepRunning) {
                    while(runtime.state()!=org.mod.openwac.core.machine.MachineState.OFF && System.nanoTime()<deadline) { runtime.tick(); Thread.sleep(1); }
                    require(runtime.state()==org.mod.openwac.core.machine.MachineState.OFF,"Installer should exit normally");
                }
                String rendered=reports.get(0).render(UUID.randomUUID(),"minecraft:overworld","1, 2, 3");
                require(rendered.contains("Kernel SHA-256") && rendered.contains("Installer PID") && rendered.contains("Kernel initialization"), "Rendered warning must include context");
            }
        }
    }
    private static void installationAndCalls() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] kernel = kernel(0);
            Code after = new Code().sys(1, 1).loadEquals(128, 17).sys(2, 8).loadEquals(128, 1).loadEquals(132, 1)
                    .sys(10, 1).loadEquals(128, 1).sys(11, -33).sys(12, -5)
                    .sys(8, 0).sys(14, 1).loadEquals(128, 3).sys(15, -1);
            // Request too large for the kernel input buffer.
            after.i(1).i(64).i(65).i(128).i(0).call(1).eq(-4);
            // Negative range and out-of-range guest pointers.
            after.i(1).i(-1).i(0).i(128).i(0).call(1).eq(-3);
            after.i(1).i(65535).i(2).i(128).i(0).call(1).eq(-8);
            after.i(1).i(0).i(131073).i(0).i(131072).call(1).eq(-11);
            // Installation cannot replace an existing kernel.
            after.i(1024).i(kernel.length).call(0).eq(-15);
            long id = f.spawn(installer(kernel, 0, after), true);
            var first = f.tick();
            require(f.manager.size() == 1 && first.runnable(), "Installation must wait across a tick boundary");
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork());
            f.noFailures();
            require(f.budget.usedPages() == 2, "Passive kernel must retain its own RAM after installer exit");
            f.spawn(caller(new Code().sys(9, 8).loadEquals(128, (int)id).loadEquals(132, 0)), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
        try (Fixture f = new Fixture()) {
            f.spawn(caller(new Code().sys(1, -13)), false);
            f.until(() -> f.manager.size() == 0); f.noFailures();
            f.spawn(installer(kernel(0), 0, new Code().sys(4, -999).op(0)), true);
            // -101 must terminate normally instead of reaching the impossible comparison/unreachable.
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
    }
    private static void blockingAndWake() throws Exception {
        try (Fixture f = new Fixture()) {
            long id = f.spawn(installer(kernel(0), 0, new Code().sys(3, 1).loadEquals(128, 17)), true);
            f.until(() -> f.created.containsKey(id) && !f.last.runnable() && f.manager.size() == 1);
            require(f.manager.wake(id), "Blocked caller must accept a wake");
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
            require(!f.manager.wake(id) && !f.manager.wake(-1), "Unknown wake targets must fail");
        }
        try (Fixture f = new Fixture()) {
            f.spawn(installer(kernel(0), 0, new Code().sys(7, 1).loadEquals(128, 17)), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
        try (Fixture f = new Fixture()) {
            long id = f.spawn(installer(kernel(0), 0, new Code().sys(3, 1)), true);
            require(f.manager.wake(id), "Pending processes must retain sticky wake tokens");
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
    }
    private static void contentionAndKills() throws Exception {
        try (Fixture f = new Fixture()) {
            long owner = f.spawn(installer(kernel(0), 0, new Code().sys(6, 0)), true);
            f.until(() -> f.created.containsKey(owner) && f.created.get(owner).inKernel() && f.budget.usedPages() == 2);
            long waiter = f.spawn(caller(new Code().sys(2, 8).loadEquals(132, 0).sys(10, 1).loadEquals(128, 1)), false);
            // Owner must stay alive while a kill is queued during suspended kernel execution.
            f.manager.queueKill(owner);
            f.tick();
            require(f.created.get(owner).inKernel(), "Kernel call must retain ownership across quantum and tick suspension");
            require(f.manager.size() == 2, "Deferred kill must not close an active kernel store");
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
            require(waiter > owner && f.panics.isEmpty(), "Kernel waiter must resume after the deferred kill");
        }
    }
    private static void exitReasons() throws Exception {
        try (Fixture f = new Fixture()) {
            long id = f.spawn(installer(kernel(0), 0, new Code().sys(3, 1)), true);
            f.until(() -> !f.last.runnable() && f.manager.size() == 1);
            f.manager.queueKill(id);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork());
            f.spawn(caller(new Code().sys(9, 8).loadEquals(128, (int)id).loadEquals(132, 2)), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
        try (Fixture f = new Fixture()) {
            f.spawn(installer(kernel(0), 0, new Code()), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork());
            long id = f.spawn(caller(new Code().op(0)), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork());
            require(f.failures.size() == 1 && f.failures.get(0).processId() == id, "Guest trap must be reported");
            f.failures.clear();
            f.spawn(caller(new Code().sys(9, 8).loadEquals(128, (int)id).loadEquals(132, 1)), true);
            f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
        }
    }
    private static void panicAndHooks() throws Exception {
        try (Fixture f = new Fixture()) {
            long installer = f.spawn(installer(kernel(0), 0, new Code().sys(3, 1)), true);
            f.until(() -> !f.last.runnable() && f.manager.size() == 1);
            f.spawn(caller(new Code().sys(5, -14).sys(1, -14)), true);
            f.spawn(caller(new Code().sys(1, -14)), false);
            // Panic wakes a privileged caller already blocked in a syscall; its retry sees -14.
            f.until(() -> f.panics.size() == 1 && !f.created.get(installer).inKernel());
            f.until(() -> f.manager.size() == 0);
            // Installer's original expectation of a successful retry deliberately traps after panic.
            require(f.failures.size() == 1 && f.failures.get(0).processId() == installer, "Panic must resume blocked privileged callers");
            f.spawn(installer(kernel(0), -15, new Code()), true);
            f.until(() -> f.manager.size() == 0);
            require(f.panics.size() == 1, "Kernel panic must be sticky and reported once");
        }
        for (int mode : new int[] {4, 5, 6}) {
            try (Fixture f = new Fixture()) {
                f.spawn(installer(kernel(mode), 0, new Code()), true);
                f.until(() -> f.manager.size() == 0 && !f.computer.hasWork());
                require(f.panics.size() == 1, "Trapped, yielding, or unbounded exit hooks must panic");
            }
        }
    }
    private static void invalidInstallations() throws Exception {
        for (int mode : new int[] {1, 2, 3, 8, 9, 10}) {
            try (Fixture f = new Fixture()) {
                f.spawn(installer(kernel(mode), -16, new Code()), true);
                f.until(() -> f.manager.size() == 0); f.noFailures();
                require(f.computer.kernelFailure() != null, "Handled installation errors must retain quiet diagnostic context");
                require(f.budget.usedPages() == 0, "Failed installation must release all kernel memory");
                f.spawn(installer(kernel(0), 0, new Code()), true);
                f.until(() -> f.manager.size() == 0 && !f.computer.hasWork()); f.noFailures();
            }
        }
        try (Fixture f = new Fixture()) {
            f.spawn(installer(new byte[] {0,97,115,109,1,0,0,0}, -16, new Code()), true);
            f.until(() -> f.manager.size() == 0); f.noFailures();
        }
        try (Fixture optional = new Fixture()) {
            optional.spawn(installer(kernel(7), 0, new Code().sys(10, 1).loadEquals(128, 0)), true);
            optional.until(() -> optional.manager.size() == 0 && !optional.computer.hasWork()); optional.noFailures();
        }
        try (Fixture small = new Fixture(65536)) {
            small.spawn(installer(kernel(0), -16, new Code()), true);
            small.until(() -> small.manager.size() == 0); small.noFailures();
            require(small.budget.usedPages() == 0, "Kernel and installer must compete for installed RAM");
        }
        Fixture active = new Fixture();
        long id = active.spawn(installer(kernel(11), 0, new Code()), true);
        active.until(() -> active.created.containsKey(id) && active.created.get(id).inKernel() && active.budget.usedPages() == 2);
        active.tick(); active.close();
        Fixture f = new Fixture();
        f.spawn(installer(kernel(0), 0, new Code()), true); f.tick(); f.close();
        await(() -> f.budget.usedPages() == 0, "Shutdown during compilation must release guest and kernel pages");
    }
    private static void bootloader() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("docs/bootloader.wasm"));
        byte[] bundled;
        try (var stream = KernelChecks.class.getResourceAsStream("/data/openwac/firmware/bootloader-v1.wasm")) {
            require(stream != null, "Bootloader resource must be bundled"); bundled = stream.readAllBytes();
        }
        require(Arrays.equals(source, bundled), "Bundled bootloader must exactly match supplied binary");
        try (Fixture f = new Fixture()) {
            f.spawn(bundled, true);
            // With no disks this bootloader draws/retries or exits; it must link all imports.
            for (int i = 0; i < 20 && f.manager.size() != 0; i++) f.tick();
            f.noFailures();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ProcessManager manager = new ProcessManager(16);
        final MemoryBudget budget;
        final Map<Long, WasmProcess> created = new HashMap<>();
        final org.mod.openwac.service.KernelInstallReports reports = new org.mod.openwac.service.KernelInstallReports();
        final List<String> panics = new ArrayList<>();
        final List<ProcessManager.Failure> failures = new ArrayList<>();
        final WasmComputer computer;
        ProcessManager.TickResult last = new ProcessManager.TickResult(0, 0, true, List.of());
        Fixture() { this(1024 * 1024); }
        Fixture(long memoryBytes) {
            budget = new MemoryBudget(memoryBytes);
            var host = new HostServices(new DeviceBus(), new EventHub(64), text -> true, new HostServices.ProcessControl() {
                @Override public long spawn(byte[] bytes, boolean privileged) { return Fixture.this.spawn(bytes, privileged); }
                @Override public boolean kill(long id) { return manager.queueKill(id); }
                @Override public boolean wake(long id) { return manager.wake(id); }
                @Override public void resume(long id) { manager.resume(id); }
            }, new HostServices.MachineMetrics() {
                @Override public long cpuTimeNanos() { return 0; }
                @Override public long ramUsedPages() { return budget.usedPages(); }
            });
            computer = new ChicoryEngine().createComputer(new EngineLimits(1024 * 1024, memoryBytes, 8), budget, host, 16,
                    message -> { panics.add(message); manager.killUnprivileged(); });
            computer.onKernelInstallFailure(reports::offer);
            manager.lifecycle(computer);
        }
        long spawn(byte[] wasm, boolean privileged) {
            return manager.spawn(wasm, privileged, (id, bytes, tier) -> {
                var process = computer.createProcess(id, bytes, tier); created.put(id, process); return process;
            });
        }
        ProcessManager.TickResult tick() {
            last = manager.runTick(64, 8); failures.addAll(last.failures());
            require(last.fuelUsed() <= 64, "Kernel work must stay within computer tick fuel"); return last;
        }
        void until(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!done.getAsBoolean() && System.nanoTime() < deadline) { tick(); Thread.sleep(1); }
            require(done.getAsBoolean(), "Kernel fixture did not finish: " + last + "; failures=" + failures);
        }
        void noFailures() { require(failures.isEmpty(), "Unexpected guest failure: " + failures); }
        @Override public void close() throws Exception {
            manager.close(); computer.close(); await(() -> budget.usedPages() == 0, "Computer close must release every page");
        }
    }
    private static byte[] kernel(int mode) {
        Module m = new Module();
        int nothing = m.type(new int[0], new int[0]);
        int result = m.type(new int[0], new int[] {I32});
        int dispatch = m.type(new int[] {I64,I32,I32,I32,I32}, new int[] {I32});
        int wake = m.type(new int[] {I64}, new int[] {I32});
        m.imp("hal", "hal_yield", nothing); m.imp("hal", "hal_wake", wake);
        m.imp("hal", "hal_ram_used_pages", m.type(new int[0],new int[] {I64}));
        if(mode==13) m.imp("hal","missing_kernel_import",nothing);
        m.function(mode == 9 ? nothing : result,
                mode == 9 ? new Code() : new Code().i(mode == 1 ? 65530 : 0), "kernel_get_io_buffers");
        Code code = new Code();
        code.local(2).i(2).op(0x46,0x04,0x40).i(128).local(0).op(0xa7,0x36,2,0)
                .i(132).local(1).op(0x36,2,0).i(8).op(0x0f,0x0b);
        // Blocking/retry and wake-before-block share a one-shot flag.
        code.local(2).i(3).op(0x46).local(2).i(7).op(0x46,0x72,0x04,0x40)
                .i(256).op(0x28,2,0,0x45,0x04,0x40).i(256).i(1).op(0x36,2,0)
                .local(2).i(7).op(0x46,0x04,0x40).local(0).call(1).op(0x1a,0x0b)
                .i(-100).op(0x0f,0x0b,0x0b);
        code.local(2).i(4).op(0x46,0x04,0x40).i(-101).op(0x0f,0x0b);
        code.local(2).i(5).op(0x46,0x04,0x40,0,0x0b);
        code.local(2).i(6).op(0x46,0x04,0x40);
        for (int i=0; i<200; i++) code.i(0).op(0x1a);
        code.i(0).op(0x0f,0x0b);
        code.local(2).i(8).op(0x46,0x04,0x40).i(1).op(0x40,0,0x1a).i(0).op(0x0f,0x0b);
        code.local(2).i(14).op(0x46,0x04,0x40).i(128).call(2).op(0xa7,0x36,2,0).i(1).op(0x0f,0x0b);
        code.local(2).i(15).op(0x46,0x04,0x40).i(14).op(0x40,0,0x0f,0x0b);
        code.local(2).i(9).op(0x46,0x04,0x40).i(128).i(272).op(0x28,2,0,0x36,2,0)
                .i(132).i(280).op(0x28,2,0,0x36,2,0).i(8).op(0x0f,0x0b);
        code.local(2).i(10).op(0x46,0x04,0x40).i(128).i(260).op(0x28,2,0,0x3a,0,0).i(1).op(0x0f,0x0b);
        code.local(2).i(11).op(0x46,0x04,0x40).i(-33).op(0x0f,0x0b);
        code.local(2).i(12).op(0x46,0x04,0x40).i(65).op(0x0f,0x0b);
        code.i(128).i(64).op(0x2d,0,0,0x3a,0,0).i(1);
        m.function(mode == 2 ? result : dispatch, mode == 2 ? new Code().i(0) : code, "kernel_syscall");
        m.function(mode == 7 ? result : nothing, mode == 11 ? new Code().op(0x03,0x40,0x0c,0,0x0b) : mode == 7 ? new Code().i(0) : mode == 3 ? new Code().op(0) : new Code().i(260).i(1).op(0x36,2,0), "kernel_init");
        int exit = m.type(new int[] {I64,I32}, new int[0]);
        Code hook = new Code().i(272).local(0).op(0xa7,0x36,2,0).i(280).local(1).op(0x36,2,0);
        if (mode == 4) hook.op(0);
        if (mode == 5) hook.call(0);
        if (mode == 6) hook.op(0x03,0x40,0x0c,0,0x0b);
        m.function(exit, hook, "kernel_process_exited");
        m.data(0, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(mode == 8 ? 65536 : 64).putInt(128).putInt(64).array());
        if(mode==12) m.exports.remove("kernel_syscall");
        if(mode==14){m.initialPages=17;m.maximumPages=32;}
        m.exportMemory = mode != 10;
        return m.build();
    }
    private static Module guest(boolean install) {
        Module m = new Module();
        m.imp("hal", install ? INSTALL : "hal_yield", install
                ? m.type(new int[] {I32,I32}, new int[] {I32}) : m.type(new int[0], new int[0]));
        m.imp("sys", "syscall", m.type(new int[] {I32,I32,I32,I32,I32}, new int[] {I32}));
        m.data(64, new byte[] {17}); return m;
    }
    private static byte[] installer(byte[] kernel, int expected, Code after) {
        Module m = guest(true);
        m.data(1024, kernel);
        m.function(m.type(new int[0], new int[0]), new Code().i(1024).i(kernel.length).call(0).eq(expected).bytes(after.bytes()), "_start");
        return m.build();
    }
    private static byte[] caller(Code code) {
        Module m = guest(false); m.function(m.type(new int[0],new int[0]), code, "_start"); return m.build();
    }
    private static final class Code {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Code op(int... ops) { for (int op:ops) out.write(op); return this; }
        Code i(int value) { out.write(0x41); signed(out,value); return this; }
        Code call(int index) { out.write(0x10); uleb(out,index); return this; }
        Code local(int index) { out.write(0x20); uleb(out,index); return this; }
        Code eq(int value) { return i(value).op(0x47,0x04,0x40,0,0x0b); }
        Code sys(int number,int expected) { return i(number).i(64).i(1).i(128).i(64).call(1).eq(expected); }
        Code loadEquals(int pointer,int value) { return i(pointer).op(0x28,2,0).eq(value); }
        Code bytes(byte[] bytes) { out.writeBytes(bytes); return this; }
        byte[] bytes() { return out.toByteArray(); }
    }
    private static final class Module {
        boolean exportMemory = true;
        int initialPages=1, maximumPages=16;
        final List<byte[]> types=new ArrayList<>(), imports=new ArrayList<>(), functions=new ArrayList<>(), data=new ArrayList<>();
        final List<Integer> functionTypes=new ArrayList<>();
        final Map<String,int[]> exports=new LinkedHashMap<>();
        int type(int[] params,int[] results) {
            var b=new ByteArrayOutputStream(); b.write(0x60); uleb(b,params.length); for(int p:params)b.write(p);
            uleb(b,results.length); for(int r:results)b.write(r); types.add(b.toByteArray()); return types.size()-1;
        }
        void imp(String module,String name,int type) {
            var b=new ByteArrayOutputStream(); name(b,module); name(b,name); b.write(0); uleb(b,type); imports.add(b.toByteArray());
        }
        void function(int type,Code code,String name) {
            int id=imports.size()+functions.size(); functionTypes.add(type);
            var b=new ByteArrayOutputStream(); b.write(0); b.writeBytes(code.bytes()); b.write(0x0b); functions.add(b.toByteArray());
            exports.put(name,new int[] {0,id});
        }
        void data(int offset,byte[] bytes) {
            var b=new ByteArrayOutputStream(); b.write(0); b.write(0x41); signed(b,offset); b.write(0x0b);
            uleb(b,bytes.length); b.writeBytes(bytes); data.add(b.toByteArray());
        }
        byte[] build() {
            var out=new ByteArrayOutputStream(); out.writeBytes(new byte[] {0,97,115,109,1,0,0,0});
            vector(out,1,types,false); if(!imports.isEmpty())vector(out,2,imports,false);
            var fs=new ByteArrayOutputStream(); uleb(fs,functionTypes.size()); for(int t:functionTypes)uleb(fs,t); section(out,3,fs.toByteArray());
            section(out,5,new byte[] {1,1,(byte)initialPages,(byte)maximumPages});
            var es=new ByteArrayOutputStream(); uleb(es,exports.size()+(exportMemory?1:0)); if(exportMemory){name(es,"memory"); es.write(2); es.write(0);}
            exports.forEach((n,v)-> {name(es,n); es.write(v[0]); uleb(es,v[1]);}); section(out,7,es.toByteArray());
            vector(out,10,functions,true); if(!data.isEmpty())vector(out,11,data,false); return out.toByteArray();
        }
    }
    private static void vector(ByteArrayOutputStream out,int section,List<byte[]> values,boolean lengths) {
        var b=new ByteArrayOutputStream(); uleb(b,values.size()); for(byte[] value:values){if(lengths)uleb(b,value.length);b.writeBytes(value);} section(out,section,b.toByteArray());
    }
    private static void name(ByteArrayOutputStream out,String value){byte[]b=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);uleb(out,b.length);out.writeBytes(b);}
    private static void section(ByteArrayOutputStream out,int id,byte[]b){out.write(id);uleb(out,b.length);out.writeBytes(b);}
    private static void uleb(ByteArrayOutputStream out,int value){do{int b=value&127;value>>>=7;out.write(b|(value==0?0:128));}while(value!=0);}
    private static void signed(ByteArrayOutputStream out,long value){while(true){int b=(int)value&127;value>>=7;boolean end=(value==0&&(b&64)==0)||(value==-1&&(b&64)!=0);out.write(b|(end?0:128));if(end)return;}}
    private static void await(BooleanSupplier condition,String message)throws Exception{long deadline=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);require(condition.getAsBoolean(),message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
