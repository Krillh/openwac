package org.mod.openwac.service;

import org.mod.openwac.core.device.Device;
import org.mod.openwac.core.event.EventHub;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.core.machine.*;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.*;
import org.mod.openwac.runtime.chicory.ChicoryEngine;
import org.mod.openwac.runtime.hal.HostServices;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Nonblocking platform-facing controller. At most one worker turn is admitted per computer. */
public final class ComputerRuntime implements AutoCloseable, HostServices.MachineMetrics {
    private static final Semaphore MACHINE_SLOTS = new Semaphore(16);
    private static final Set<ComputerRuntime> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> Thread.ofPlatform().daemon().name("openwac-machine").unstarted(runnable));
    private final VirtualMachine machine;
    private final MemoryBudget memory;
    private final WasmComputer execution;
    private final WasmEngine engine = new ChicoryEngine();
    private final KernelInstallReports kernelInstallReports = new KernelInstallReports();
    private final ArrayBlockingQueue<String> chat = new ArrayBlockingQueue<>(16);
    private final AtomicInteger chatAllowance = new AtomicInteger(4);
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean restartRequested = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile boolean closed;
    private volatile long lastTickCpuTimeNanos;
    private volatile ComputerCrashReport crashReport;

    public ComputerRuntime(UUID id, Collection<Device> components, MachineConfig config) {
        if (!MACHINE_SLOTS.tryAcquire()) throw new IllegalStateException("Server active computer limit reached");
        try {
            machine = new VirtualMachine(id, config);
            memory = new MemoryBudget(config.maxMemoryBytes());
            var host = new HostServices(machine.devices(), new EventHub(64), this::offerChat, new HostServices.ProcessControl() {
                @Override public long spawn(byte[] bytes, boolean privileged) { return machine.processes().spawn(bytes, privileged, ComputerRuntime.this::createProcess); }
                @Override public boolean kill(long id) { return machine.processes().queueKill(id); }
                @Override public boolean wake(long id) { return machine.processes().wake(id); }
                @Override public void resume(long id) { machine.processes().resume(id); }
                @Override public void restart() { restartRequested.set(true); }
            }, this);
            execution = engine.createComputer(new EngineLimits(FirmwareRepository.MAX_MODULE_BYTES,
                    config.maxMemoryBytes(), config.quantum()), memory, host, config.maxProcesses(), machine::kernelPanic);
            execution.onKernelInstallFailure(failure -> { if (!closed) kernelInstallReports.offer(failure); });
            machine.processes().lifecycle(execution);
            machine.onCrash(crash -> crashReport = new ComputerCrashReport(crash, memory.usedPages(),
                    memory.capacityPages(), execution.kernelFailure()));
            components.forEach(machine.devices()::attach);
            machine.powerOn();
            ACTIVE.add(this);
        } catch (RuntimeException failure) { MACHINE_SLOTS.release(); throw failure; }
    }
    public MachineState state() { return machine.state(); }
    @Override public long cpuTimeNanos() { return lastTickCpuTimeNanos; }
    @Override public long ramUsedPages() { return memory.usedPages(); }
    /** DeviceBus synchronizes topology access; device implementations synchronize their own state. */
    public void attachDevice(Device device) { if (!closed) machine.devices().attach(device); }
    public void detachDevice(UUID id) { machine.devices().detach(id); }
    public String diagnostic() { return machine.diagnostic(); }
    public ComputerCrashReport crashReport() { return crashReport; }
    public boolean restartRequested() { return restartRequested.get(); }
    public List<KernelInstallFailure> drainKernelInstallFailures() { return kernelInstallReports.drain(); }
    public List<String> drainChat() {
        List<String> messages = new ArrayList<>();
        chat.drainTo(messages, 4);
        chatAllowance.set(4);
        return messages;
    }
    public void tick() {
        if (closed || restartRequested.get() || machine.state() == MachineState.OFF || machine.state() == MachineState.CRASHED || !inFlight.compareAndSet(false, true)) return;
        try {
            WORKERS.execute(() -> {
                try {
                    if (!closed) {
                        long started = System.nanoTime();
                        try {
                            machine.runTick(this::createProcess);
                            if (restartRequested.get()) close();
                            if (machine.state() == MachineState.OFF || machine.state() == MachineState.CRASHED) execution.close();
                        }
                        finally { lastTickCpuTimeNanos = System.nanoTime() - started; }
                    }
                } finally {
                    if (closed) release();
                    inFlight.set(false);
                    if (closed) release();
                }
            });
        } catch (RejectedExecutionException overloaded) { inFlight.set(false); if (closed) release(); }
    }
    private WasmProcess createProcess(long id, byte[] wasm, boolean privileged) {
        return execution.createProcess(id, wasm, privileged);
    }
    private boolean offerChat(String message) {
        if (closed) return false;
        int remaining;
        do {
            remaining = chatAllowance.get();
            if (remaining <= 0) return false;
        } while (!chatAllowance.compareAndSet(remaining, remaining - 1));
        return chat.offer(message);
    }
    private void release() {
        if (released.compareAndSet(false, true)) {
            machine.powerOff();
            execution.close();
            ACTIVE.remove(this);
            MACHINE_SLOTS.release();
            stopped.complete(null);
        }
    }
    @Override public void close() {
        closed = true;
        if (!inFlight.get()) release();
    }
    /** Completes after the last worker turn and all guest execution have stopped. */
    public CompletableFuture<Void> stopped() { return stopped.copy(); }
    public static void shutdownAll() { List.copyOf(ACTIVE).forEach(ComputerRuntime::close); }
}
