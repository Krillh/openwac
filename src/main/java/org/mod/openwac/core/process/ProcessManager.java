package org.mod.openwac.core.process;

import java.util.*;
import org.mod.openwac.core.diagnostic.FailureDetails;

/** Owned by one machine execution worker. The ready queue survives tick boundaries. */
public final class ProcessManager implements AutoCloseable {
    public record Failure(long processId, String diagnostic, FailureDetails details) {
        public Failure(long processId, String diagnostic) { this(processId, diagnostic, null); }
    }
    public record TickResult(long fuelUsed, int turns, boolean runnable, List<Failure> failures) {}
    public interface Lifecycle {
        default void exited(long id, int reason) {}
        default void drainCommands() {}
        default boolean canSpawn(int pending) { return true; }
        default void bootStarted(long id) {}
        default void bootExited(long id, int reason) {}
        default boolean canStartProcesses() { return true; }
        default long maintenanceProcessId() { return 0; }
        default boolean hasWork() { return false; }
        default WasmProcess.Step maintenance(long fuel) { return new WasmProcess.Step(0, WasmProcess.Reason.EXITED, null); }
    }
    private record Spawn(long id, byte[] wasm, boolean privileged, ProcessFactory factory) {}
    private final Map<Long, WasmProcess> processes = new LinkedHashMap<>();
    private final Map<Long, Boolean> privileges = new HashMap<>();
    private final Map<Long, WasmProcess.Reason> waiting = new HashMap<>();
    private final ArrayDeque<Long> ready = new ArrayDeque<>();
    private final ArrayDeque<Spawn> pendingSpawns = new ArrayDeque<>();
    private final Set<Long> pendingKills = new LinkedHashSet<>(), pendingWakes = new HashSet<>();
    private Lifecycle lifecycle = new Lifecycle() {};
    private final int limit;
    private long nextId = 1;
    private long bootId;

    public ProcessManager(int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid process limit");
        this.limit = limit;
    }
    public void lifecycle(Lifecycle value) { lifecycle = Objects.requireNonNull(value); }
    /** The BIOS runs alone until it exits; other spawn requests retain their allocated IDs. */
    public long spawnBoot(byte[] wasm, ProcessFactory factory) {
        if (size() != 0 || bootId != 0) throw new IllegalStateException("Computer already has processes");
        long id = spawn(wasm, true, factory);
        if (id != 0) { bootId = id; lifecycle.bootStarted(id); }
        return id;
    }
    public long spawn(byte[] wasm, boolean privileged, ProcessFactory factory) {
        if (wasm == null || wasm.length < 8 || wasm.length > 1024 * 1024 || size() >= limit
                || !lifecycle.canSpawn(pendingSpawns.size()) || nextId <= 0) return 0;
        long id = nextId++;
        pendingSpawns.addLast(new Spawn(id, wasm.clone(), privileged, Objects.requireNonNull(factory)));
        return id;
    }
    public boolean queueKill(long id) {
        if (id <= 0 || pendingKills.size() >= 256) return false;
        pendingKills.add(id);
        WasmProcess process = processes.get(id);
        if (process != null) process.requestKill();
        return true;
    }
    public void killUnprivileged() {
        Set<Long> known = new HashSet<>(processes.keySet());
        for (Spawn spawn : pendingSpawns) known.add(spawn.id());
        pendingKills.retainAll(known);
        privileges.forEach((id, privileged) -> { if (!privileged) queueKill(id); });
        for (Spawn spawn : pendingSpawns) if (!spawn.privileged()) queueKill(spawn.id());
    }
    private void applyPending(List<Failure> failures) {
        lifecycle.drainCommands();
        while (!pendingSpawns.isEmpty()
                && (bootId != 0 ? pendingSpawns.getFirst().id() == bootId : lifecycle.canStartProcesses())) {
            Spawn request = pendingSpawns.removeFirst();
            if (pendingKills.remove(request.id())) {
                pendingWakes.remove(request.id());
                lifecycle.exited(request.id(), 2);
                finishBoot(request.id(), 2);
                continue;
            }
            try {
                WasmProcess process = Objects.requireNonNull(request.factory().create(request.id(), request.wasm(), request.privileged()));
                processes.put(request.id(), process);
                privileges.put(request.id(), request.privileged());
                if (pendingWakes.remove(request.id())) process.wake();
                ready.addLast(request.id());
            } catch (RuntimeException failure) {
                pendingWakes.remove(request.id());
                var details = FailureDetails.capture("Process creation", failure);
                failures.add(new Failure(request.id(), details.summary(), details));
                lifecycle.exited(request.id(), 1);
                finishBoot(request.id(), 1);
            }
        }
        for (long id : List.copyOf(pendingKills)) {
            Spawn pending = pendingSpawns.stream().filter(spawn -> spawn.id() == id).findFirst().orElse(null);
            if (pending != null) {
                pendingSpawns.remove(pending); pendingKills.remove(id); pendingWakes.remove(id);
                lifecycle.exited(id, 2);
                continue;
            }
            WasmProcess process = processes.get(id);
            if (process == null || !process.inKernel()) {
                pendingKills.remove(id);
                remove(id, 2);
            }
        }
    }
    public TickResult runTick(long totalFuel, long quantum) {
        if (totalFuel < 1 || quantum < 1) throw new IllegalArgumentException("Invalid fuel budget");
        long remaining = totalFuel;
        int turns = 0;
        List<Failure> failures = new ArrayList<>();
        for (long id : List.copyOf(waiting.keySet())) {
            if (waiting.get(id) == WasmProcess.Reason.NEXT_TICK) { waiting.remove(id); enqueueReady(id); }
        }
        applyPending(failures);
        if (lifecycle.hasWork()) {
            var step = lifecycle.maintenance(remaining);
            if (step.fuelUsed() > remaining) throw new IllegalStateException("Maintenance exceeded tick fuel");
            remaining -= Math.max(1, step.fuelUsed());
            turns++;
            if (step.reason() == WasmProcess.Reason.TRAPPED && lifecycle.maintenanceProcessId() != 0) {
                failures.add(new Failure(lifecycle.maintenanceProcessId(), Objects.toString(step.diagnostic(), "Maintenance failed")));
                pendingSpawns.clear(); pendingWakes.clear();
            }
            applyPending(failures);
        }
        while (remaining > 0 && !ready.isEmpty()) {
            long id = ready.removeFirst();
            WasmProcess process = processes.get(id);
            if (process == null) continue;
            long granted = Math.min(quantum, remaining);
            WasmProcess.Step step;
            FailureDetails turnFailure = null;
            try { step = process.runQuantum(granted); }
            catch (RuntimeException failure) {
                turnFailure = FailureDetails.capture("Process scheduling", failure);
                step = new WasmProcess.Step(granted, WasmProcess.Reason.TRAPPED, turnFailure.summary());
            }
            if (step.fuelUsed() > granted) throw new IllegalStateException("Runtime exceeded its quantum");
            remaining -= Math.max(1, step.fuelUsed());
            turns++;
            switch (step.reason()) {
                case QUANTUM_EXHAUSTED, YIELDED -> enqueueReady(id);
                case BLOCKED, NEXT_TICK, KERNEL_WAIT -> waiting.put(id, step.reason());
                case EXITED -> remove(id, 0);
                case KILLED -> remove(id, 2);
                case TRAPPED -> {
                    failures.add(new Failure(id, Objects.toString(step.diagnostic(), "WASM trap"),
                            turnFailure != null ? turnFailure : process.failureDetails()));
                    remove(id, 1);
                }
            }
            applyPending(failures);
        }
        return new TickResult(totalFuel - remaining, turns, !ready.isEmpty() || hasMaintenance()
                || waiting.containsValue(WasmProcess.Reason.NEXT_TICK), List.copyOf(failures));
    }
    private void enqueueReady(long id) { if (processes.containsKey(id) && !ready.contains(id)) ready.addLast(id); }
    public boolean wake(long id) {
        WasmProcess process = processes.get(id);
        if (process == null) {
            if (pendingSpawns.stream().noneMatch(spawn -> spawn.id() == id)) return false;
            pendingWakes.add(id);
            return true;
        }
        process.wake();
        if (waiting.get(id) == WasmProcess.Reason.BLOCKED) { waiting.remove(id); enqueueReady(id); }
        return true;
    }
    /** Kernel ownership becoming available is separate from a sticky syscall wake. */
    public void resume(long id) {
        if (waiting.get(id) == WasmProcess.Reason.KERNEL_WAIT) { waiting.remove(id); enqueueReady(id); }
    }
    public void kill(long id) {
        WasmProcess process = processes.get(id);
        if (process != null && process.inKernel()) queueKill(id); else remove(id, 2);
    }
    private void remove(long id, int reason) {
        ready.remove(id); waiting.remove(id); privileges.remove(id); pendingWakes.remove(id);
        WasmProcess process = processes.remove(id);
        if (process != null) { process.close(); lifecycle.exited(id, reason); }
        finishBoot(id, reason);
    }
    private void finishBoot(long id, int reason) {
        if (bootId == 0 || id != bootId) return;
        bootId = 0;
        if (reason != 0) { pendingSpawns.clear(); pendingWakes.clear(); }
        lifecycle.bootExited(id, reason);
    }
    public boolean hasMaintenance() { return lifecycle.hasWork(); }
    public int size() { return processes.size() + pendingSpawns.size(); }
    @Override public void close() {
        processes.values().forEach(WasmProcess::close);
        processes.clear(); privileges.clear(); waiting.clear(); ready.clear();
        pendingSpawns.clear(); pendingKills.clear(); pendingWakes.clear();
        if (bootId != 0) finishBoot(bootId, 2);
    }
}
