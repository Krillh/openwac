package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.core.machine.MemoryBudget;
import org.mod.openwac.runtime.hal.HostServices;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** A parked virtual thread preserves guest and kernel continuations across scheduler turns. */
final class ChicoryProcess implements WasmProcess {
    private static final Semaphore PROCESS_SLOTS = new Semaphore(128);
    @FunctionalInterface interface Program { void run(ChicoryProcess process) throws Exception; }
    static final class NormalExit extends RuntimeException { NormalExit() { super(null, null, false, false); } }
    static final class Restart extends RuntimeException { Restart() { super(null, null, false, false); } }
    private static final class Killed extends RuntimeException { Killed() { super(null, null, false, false); } }
    private final BlockingQueue<Long> grants = new ArrayBlockingQueue<>(1);
    private final BlockingQueue<Step> results = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean wakeToken = new AtomicBoolean();
    private final Thread thread;
    private volatile boolean closed, finished, inKernel, killRequested;
    private Step terminalResult;
    private volatile String phase = "Waiting for execution";
    private final java.util.concurrent.atomic.AtomicReference<FailureDetails> failureDetails = new java.util.concurrent.atomic.AtomicReference<>();
    private long remaining, granted;
    private final long maxQuantum;
    final long id;
    final boolean privileged, callback;
    final ChicoryComputer computer;

    ChicoryProcess(ChicoryEngine engine, byte[] wasm, EngineLimits limits, MemoryBudget memory, HostServices host, boolean privileged) {
        this(engine, wasm, limits, memory, host, privileged, 1, null);
    }
    ChicoryProcess(ChicoryEngine engine, byte[] wasm, EngineLimits limits, MemoryBudget memory, HostServices host,
            boolean privileged, long id, ChicoryComputer computer) {
        this(id, privileged, computer, limits.fuelPerTurn(), false, process -> {
            BudgetedMemory[] allocated = new BudgetedMemory[1];
            process.phase = "Module compilation";
            try (var compiled = (ChicoryEngine.ParsedModule) engine.compile(wasm, limits)) {
                process.phase = "Module linking/instantiation";
                Instance instance = instantiate(compiled, limits, memory,
                        new ChicoryHostImports(host, privileged, () -> process, false).imports(), () -> process,
                        value -> allocated[0] = value);
                var start = instance.exportType("_start");
                if (!start.params().isEmpty() || !start.returns().isEmpty())
                    throw new IllegalArgumentException("_start must have type () -> ()");
                process.phase = "Guest execution";
                instance.export("_start").apply();
            } finally {
                if (allocated[0] != null) allocated[0].close();
            }
        });
    }
    ChicoryProcess(long id, boolean privileged, ChicoryComputer computer, long maxQuantum, boolean callback, Program program) {
        this.id = id; this.privileged = privileged; this.computer = computer;
        this.maxQuantum = maxQuantum; this.callback = callback;
        if (!callback && !PROCESS_SLOTS.tryAcquire()) throw new IllegalStateException("Server WASM process limit reached");
        thread = Thread.ofVirtual().name(callback ? "openwac-kernel-exit" : "openwac-wasm").unstarted(() -> {
            Reason reason = Reason.TRAPPED;
            String diagnostic = "Execution failed";
            try {
                awaitGrant();
                program.run(this);
                reason = killRequested ? Reason.KILLED : Reason.EXITED; diagnostic = null;
            } catch (Killed killed) {
                reason = Reason.KILLED; diagnostic = null;
            } catch (NormalExit exit) {
                reason = killRequested ? Reason.KILLED : Reason.EXITED; diagnostic = null;
            } catch (Restart restart) {
                reason = Reason.EXITED; diagnostic = null;
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt(); diagnostic = "Execution cancelled";
            } catch (Exception | StackOverflowError failure) {
                failureDetails.compareAndSet(null, FailureDetails.capture(phase, failure));
                diagnostic = failureDetails.get().summary();
            } finally {
                if (computer != null) computer.abandon(this);
                if (!callback) PROCESS_SLOTS.release();
                terminalResult = new Step(granted - remaining, reason, diagnostic);
                if (!closed) results.offer(terminalResult);
                finished = true;
            }
        });
        try { thread.start(); }
        catch (RuntimeException | Error failure) { if (!callback) PROCESS_SLOTS.release(); throw failure; }
    }
    static Instance instantiate(ChicoryEngine.ParsedModule compiled, EngineLimits limits, MemoryBudget budget,
            ImportValues imports, Supplier<ChicoryProcess> current, Consumer<BudgetedMemory> allocated) {
        MeteredInterpreter[] interpreter = new MeteredInterpreter[1];
        return Instance.builder(compiled.module()).withStart(false)
                .withMachineFactory(value -> interpreter[0] = new MeteredInterpreter(value))
                .withMemoryFactory(memory -> {
                    int max = (int) Math.min(memory.maximumPages(), limits.maxMemoryBytes() / Memory.PAGE_SIZE);
                    if (memory.initialPages() > max || memory.shared()) throw new IllegalArgumentException("Invalid memory limits");
                    var result = new BudgetedMemory(new MemoryLimits(memory.initialPages(), max), budget);
                    allocated.accept(result);
                    return result;
                })
                .withUnsafeExecutionListener((instruction, stack) -> {
                    if (stack.size() > 65536 || interpreter[0].depth() > 128) throw new IllegalStateException("WASM stack limit exceeded");
                    current.get().charge(1);
                }).withImportValues(imports).build();
    }
    private static final class MeteredInterpreter extends InterpreterMachine {
        MeteredInterpreter(Instance instance) { super(instance); }
        int depth() { return callStack.size(); }
    }
    @Override public synchronized Step runQuantum(long fuel) {
        if (fuel < 1 || fuel > maxQuantum) throw new IllegalArgumentException("Quantum outside engine limits");
        Step terminal = results.poll();
        if (terminal != null) return terminal;
        if (finished) return terminalResult;
        if (closed) return new Step(0, Reason.EXITED, null);
        if (!grants.offer(fuel)) throw new IllegalStateException("A quantum is already running");
        try {
            Step result = results.poll(2, TimeUnit.SECONDS);
            if (result != null) return result;
            var timeout = new TimeoutException("Execution turn exceeded the two-second wall clock limit during " + phase);
            StackTraceElement[] running = thread.getStackTrace();
            if (running.length != 0) timeout.setStackTrace(running);
            failureDetails.compareAndSet(null, FailureDetails.capture("Watchdog timeout", timeout));
            close();
            return new Step(fuel, Reason.TRAPPED, failureDetails.get().summary());
        } catch (InterruptedException cancelled) {
            failureDetails.compareAndSet(null, FailureDetails.capture("Execution worker interrupted", cancelled));
            Thread.currentThread().interrupt(); close();
            return new Step(fuel, Reason.TRAPPED, failureDetails.get().summary());
        }
    }
    void charge(long units) {
        if (units < 0) throw new IllegalArgumentException("Negative host cost");
        while (units > 0) {
            if (killRequested && !inKernel) throw new Killed();
            if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (remaining == 0) {
                if (callback) throw new IllegalStateException("Exit callback exhausted tick fuel");
                park(Reason.QUANTUM_EXHAUSTED);
            }
            long used = Math.min(units, remaining);
            remaining -= used; units -= used;
        }
    }
    void yieldProcess() { park(Reason.YIELDED); }
    void park(Reason reason) {
        if (killRequested && !inKernel) throw new Killed();
        if (callback) throw new IllegalStateException("Exit callbacks cannot suspend");
        if (closed) throw new CancellationException();
        results.add(new Step(granted - remaining, reason, null));
        try { awaitGrant(); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    void blockSyscall() {
        if (wakeToken.getAndSet(false)) return;
        park(Reason.BLOCKED);
        wakeToken.set(false);
    }
    private void awaitGrant() throws InterruptedException {
        long fuel = grants.take();
        if (closed) throw new InterruptedException();
        granted = fuel; remaining = fuel;
    }
    void kernelOwnership(boolean value) { inKernel = value; }
    @Override public FailureDetails failureDetails() { return failureDetails.get(); }
    @Override public void requestKill() { killRequested = true; }
    @Override public boolean inKernel() { return inKernel; }
    @Override public void wake() { wakeToken.set(true); }
    @Override public void close() { closed = true; thread.interrupt(); }
}
