package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValType;
import org.mod.openwac.core.machine.MemoryBudget;
import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.runtime.*;
import org.mod.openwac.runtime.hal.HostServices;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One passive, serialized kernel per computer. Guest bytes and runtime objects never cross machines. */
final class ChicoryComputer implements WasmComputer {
    private static final int KERNEL_INPUT_CAPACITY = 256 * 1024;
    private static final int KERNEL_OUTPUT_CAPACITY = 16 * 1024;
    private enum State { EMPTY, INSTALLING, READY, PANICKED, CLOSED }
    private record Exit(long id, int reason) {}
    private static final ThreadPoolExecutor COMPILERS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> Thread.ofPlatform().daemon().name("openwac-kernel-compiler").unstarted(task));
    private final ChicoryEngine engine;
    private final EngineLimits limits;
    private final MemoryBudget budget;
    private final HostServices host;
    private final int processLimit;
    private final Consumer<String> panicHandler;
    private final ArrayDeque<Exit> exits = new ArrayDeque<>();
    private final Set<Long> waiters = new LinkedHashSet<>(), blocked = new LinkedHashSet<>();
    private final ArrayDeque<Runnable> commands = new ArrayDeque<>();
    private State state = State.EMPTY;
    private ChicoryProcess owner, installer;
    private Future<ChicoryEngine.ParsedModule> compilation;
    private Instance kernel;
    private BudgetedMemory kernelMemory;
    private int inputPointer, inputCapacity, outputPointer, outputCapacity;
    private int preparedInputCapacity, preparedOutputCapacity;
    private boolean prepareIoBuffers;
    private boolean exitHook;
    private volatile FailureDetails kernelFailure;
    private Consumer<KernelInstallFailure> installFailureListener = failure -> {};
    @Override public synchronized void onKernelInstallFailure(Consumer<KernelInstallFailure> listener) {
        installFailureListener = Objects.requireNonNull(listener);
    }
    private synchronized void installationFailed(byte[] wasm, ChicoryProcess caller, String phase, Throwable failure) {
        if (state == State.CLOSED || Thread.currentThread().isInterrupted()) return;
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
        kernelFailure = FailureDetails.capture(phase, cause);
        installFailureListener.accept(KernelInstallFailure.capture(caller.id, wasm, kernelFailure));
    }
    @Override public FailureDetails kernelFailure() { return kernelFailure; }

    ChicoryComputer(ChicoryEngine engine, EngineLimits limits, MemoryBudget budget, HostServices host,
            int processLimit, Consumer<String> panic) {
        this.engine = engine; this.limits = limits; this.budget = budget; this.host = host;
        this.processLimit = processLimit; this.panicHandler = panic;
    }
    @Override public synchronized WasmProcess createProcess(long id, byte[] wasm, boolean privileged) {
        if (state == State.CLOSED) throw new IllegalStateException("Computer is closed");
        var processHost = new HostServices(host.devices(), new org.mod.openwac.core.event.EventHub(64),
                host.chat(), host.processes(), host.metrics());
        return new ChicoryProcess(engine, wasm.clone(), limits, budget, processHost, privileged, id, this);
    }
    private synchronized ChicoryProcess active() {
        if (owner == null) throw new IllegalStateException("Kernel has no execution context");
        return owner;
    }
    int install(byte[] wasm, ChicoryProcess caller) {
        Future<ChicoryEngine.ParsedModule> future;
        synchronized (this) {
            if (state != State.EMPTY) return -15;
            state = State.INSTALLING; installer = caller;
            try {
                future = COMPILERS.submit(() -> (ChicoryEngine.ParsedModule) engine.compile(wasm, limits));
                compilation = future;
            } catch (RejectedExecutionException full) {
                installationFailed(wasm, caller, "Kernel compilation admission", full);
                state = State.EMPTY; installer = null;
                return -16;
            }
        }
        boolean success = false;
        String phase = "Kernel compilation";
        try {
            do { caller.park(WasmProcess.Reason.NEXT_TICK); } while (!future.isDone());
            try (var compiled = future.get()) {
                if (!acquire(caller, true)) return -16;
                phase = "Kernel linking/instantiation";
                kernel = ChicoryProcess.instantiate(compiled, limits, budget,
                        new ChicoryHostImports(host, true, this::active, true).imports(), this::active,
                        value -> { synchronized (this) { kernelMemory = value; } });
                phase = "Kernel exports and I/O descriptor";
                Memory memory;
                try { memory = kernel.exports().memory("memory"); }
                catch (RuntimeException missing) {
                    throw new IllegalArgumentException("Missing required kernel export 'memory' (linear memory)", missing);
                }
                if (memory == null) throw new IllegalArgumentException("Missing required kernel export 'memory' (linear memory)");
                requireType("kernel_get_io_buffers", List.of(), List.of(ValType.I32));
                requireType("kernel_syscall", List.of(ValType.I64, ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32));
                phase = "Kernel initialization";
                if (hasType("kernel_init", List.of(), List.of())) kernel.export("kernel_init").apply();
                prepareIoBuffers = hasType("kernel_prepare_io_buffers", List.of(ValType.I32, ValType.I32), List.of(ValType.I32));
                phase = "Kernel I/O descriptor validation";
                readIoBuffers(memory);
                preparedInputCapacity = Math.min(inputCapacity, KERNEL_INPUT_CAPACITY);
                preparedOutputCapacity = Math.min(outputCapacity, KERNEL_OUTPUT_CAPACITY);
                exitHook = hasType("kernel_process_exited", List.of(ValType.I64, ValType.I32), List.of());
                synchronized (this) {
                    if (state != State.INSTALLING) return -16;
                    state = State.READY; success = true; kernelFailure = null;
                }
                return 0;
            }
        } catch (ChicoryProcess.Restart restart) { throw restart; }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (Exception | StackOverflowError failure) {
            installationFailed(wasm, caller, phase, failure);
            return -16;
        }
        finally {
            if (!success) future.cancel(true);
            synchronized (this) {
                if (installer == caller) {
                    compilation = null; installer = null;
                    if (!success && state == State.INSTALLING) state = State.EMPTY;
                }
                if (!success) disposeMemory();
            }
            release(caller);
        }
    }
    int syscall(ChicoryProcess caller, int number, byte[] request, Memory guestMemory, int responsePointer, int responseCapacity) {
        while (true) {
            if (!acquire(caller, false)) return status();
            int result;
            try {
                Memory memory = kernel.exports().memory("memory");
                if (prepareIoBuffers) {
                    if (request.length > KERNEL_INPUT_CAPACITY) return -4;
                    int input = Math.max(preparedInputCapacity, request.length);
                    int output = Math.max(preparedOutputCapacity, Math.min(responseCapacity, KERNEL_OUTPUT_CAPACITY));
                    if (input > preparedInputCapacity || output > preparedOutputCapacity) {
                        int preparation = (int) kernel.export("kernel_prepare_io_buffers").apply(input, output)[0];
                        if (preparation < 0) return preparation;
                        if (preparation != 0) throw new IllegalStateException("Invalid kernel I/O preparation status " + preparation);
                        int previousInput = inputCapacity, previousOutput = outputCapacity;
                        readIoBuffers(memory);
                        if (inputCapacity < Math.max(previousInput, input) || outputCapacity < Math.max(previousOutput, output))
                            throw new IllegalStateException("Kernel I/O buffers shrank or did not reach requested capacities");
                        preparedInputCapacity = input;
                        preparedOutputCapacity = output;
                    }
                }
                if (request.length > inputCapacity) return -4;
                int capacity = Math.min(responseCapacity, outputCapacity);
                if (prepareIoBuffers) capacity = Math.min(capacity, KERNEL_OUTPUT_CAPACITY);
                memory.write(inputPointer, request);
                result = (int) kernel.export("kernel_syscall").apply(caller.id, caller.privileged ? 1 : 0, number, request.length, capacity)[0];
                if (result > capacity) return -5;
                if (result > 0) {
                    byte[] response = memory.readBytes(outputPointer, result);
                    try { guestMemory.write(responsePointer, response); }
                    catch (RuntimeException failure) { return -9; }
                }
            } catch (ChicoryProcess.Restart restart) { throw restart; }
            catch (CancellationException cancelled) { panic("Kernel execution cancelled"); throw cancelled; }
            catch (RuntimeException | StackOverflowError failure) { panic("Kernel syscall trapped"); return -14; }
            finally { release(caller); }
            if (result == -101) throw new ChicoryProcess.NormalExit();
            if (result != -100) return result;
            synchronized (this) {
                if (state == State.PANICKED) return -14;
                blocked.add(caller.id);
            }
            try { caller.blockSyscall(); }
            finally { synchronized (this) { blocked.remove(caller.id); } }
        }
    }
    private void readIoBuffers(Memory memory) {
        int descriptor = (int) kernel.export("kernel_get_io_buffers").apply()[0];
        range(memory, descriptor, 16, "I/O descriptor");
        ByteBuffer fields = ByteBuffer.wrap(memory.readBytes(descriptor, 16)).order(ByteOrder.LITTLE_ENDIAN);
        int input = fields.getInt(), inputSize = fields.getInt();
        int output = fields.getInt(), outputSize = fields.getInt();
        range(memory, input, inputSize, "Kernel input buffer");
        range(memory, output, outputSize, "Kernel output buffer");
        inputPointer = input; inputCapacity = inputSize;
        outputPointer = output; outputCapacity = outputSize;
    }
    private synchronized int status() { return state == State.PANICKED ? -14 : -13; }
    private boolean acquire(ChicoryProcess caller, boolean installing) {
        while (true) {
            synchronized (this) {
                if (state != (installing ? State.INSTALLING : State.READY)) return false;
                if (owner == null) { owner = caller; caller.kernelOwnership(true); return true; }
                if (owner == caller) throw new IllegalStateException("Reentrant kernel call");
                waiters.add(caller.id);
            }
            caller.park(WasmProcess.Reason.KERNEL_WAIT);
        }
    }
    private synchronized void release(ChicoryProcess caller) {
        if (owner != caller) return;
        owner = null; caller.kernelOwnership(false);
        for (long id : waiters) commands.addLast(() -> host.processes().resume(id));
        waiters.clear();
        if (state == State.PANICKED || state == State.CLOSED) disposeMemory();
    }
    synchronized void abandon(ChicoryProcess caller) {
        waiters.remove(caller.id); blocked.remove(caller.id);
        if (installer == caller) {
            if (compilation != null) compilation.cancel(true);
            compilation = null; installer = null;
            if (state == State.INSTALLING) state = State.EMPTY;
        }
        if (owner == caller) {
            if (state != State.CLOSED) panic("Kernel owner stopped during execution");
            release(caller);
        }
    }
    private boolean hasType(String name, List<ValType> params, List<ValType> returns) {
        try { var type = kernel.exportType(name); return type.params().equals(params) && type.returns().equals(returns); }
        catch (RuntimeException missing) { return false; }
    }
    private void requireType(String name, List<ValType> params, List<ValType> returns) {
        com.dylibso.chicory.wasm.types.FunctionType type;
        try { type = kernel.exportType(name); }
        catch (RuntimeException missing) { throw new IllegalArgumentException("Missing required kernel export '" + name + "'; expected " + params + " -> " + returns, missing); }
        if (type == null) throw new IllegalArgumentException("Missing required kernel export '" + name + "'; expected " + params + " -> " + returns);
        if (!type.params().equals(params) || !type.returns().equals(returns))
            throw new IllegalArgumentException("Wrong signature for kernel export '" + name + "': expected " + params + " -> " + returns
                    + "; actual " + type.params() + " -> " + type.returns());
    }
    private static void range(Memory memory, int pointer, int length, String buffer) {
        if (pointer < 0 || length < 0 || (long) pointer + length > (long) memory.pages() * Memory.PAGE_SIZE)
            throw new IllegalArgumentException(buffer + " outside kernel memory: pointer=" + Integer.toUnsignedLong(pointer)
                    + ", length=" + Integer.toUnsignedLong(length) + ", memory bytes=" + (long) memory.pages() * Memory.PAGE_SIZE);
    }
    private synchronized void panic(String diagnostic) {
        if (state == State.CLOSED || state == State.PANICKED) return;
        state = State.PANICKED; exits.clear();
        commands.addLast(() -> panicHandler.accept(diagnostic));
        for (long id : blocked) commands.addLast(() -> host.processes().wake(id));
        blocked.clear();
        for (long id : waiters) commands.addLast(() -> host.processes().resume(id));
        waiters.clear();
        if (owner == null) disposeMemory();
    }
    @Override public void drainCommands() {
        while (true) {
            Runnable command;
            synchronized (this) { command = commands.pollFirst(); }
            if (command == null) return;
            command.run();
        }
    }
    @Override public synchronized boolean canSpawn(int pending) {
        return state != State.CLOSED && exits.size() + pending < processLimit;
    }
    @Override public synchronized void exited(long id, int reason) {
        if (state != State.READY || !exitHook) return;
        if (exits.size() >= processLimit) { panic("Kernel exit notification queue overflow"); return; }
        exits.addLast(new Exit(id, reason));
    }
    @Override public synchronized boolean hasWork() { return !commands.isEmpty() || (state == State.READY && owner == null && !exits.isEmpty()); }
    @Override public WasmProcess.Step maintenance(long fuel) {
        Exit exit;
        synchronized (this) {
            if (state != State.READY || owner != null || exits.isEmpty()) return new WasmProcess.Step(0, WasmProcess.Reason.EXITED, null);
            exit = exits.removeFirst();
        }
        try (var callback = new ChicoryProcess(0, true, this, fuel, true, process -> {
            if (!acquire(process, false)) return;
            try { kernel.export("kernel_process_exited").apply(exit.id(), exit.reason()); }
            finally { release(process); }
        })) {
            var result = callback.runQuantum(fuel);
            if (result.reason() != WasmProcess.Reason.EXITED) panic("Kernel exit callback trapped or suspended");
            return result;
        }
    }
    private void disposeMemory() {
        if (kernelMemory != null) { kernelMemory.close(); kernelMemory = null; }
        kernel = null;
    }
    @Override public synchronized void close() {
        state = State.CLOSED;
        if (compilation != null) compilation.cancel(true);
        compilation = null; installer = null; exits.clear(); commands.clear(); waiters.clear(); blocked.clear();
        if (owner != null) owner.close(); else disposeMemory();
    }
}
