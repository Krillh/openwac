package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import org.mod.openwac.core.device.*;
import org.mod.openwac.runtime.hal.HostServices;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Primitive-only ABI adapter. No guest pointers survive a host call. */
final class ChicoryHostImports {
    private static final int MAX_CALL = 256 * 1024;
    private final HostServices host;
    private final boolean privileged;
    private final java.util.function.Supplier<ChicoryProcess> context;
    private final boolean kernelStore;

    ChicoryHostImports(HostServices host, boolean privileged, java.util.function.Supplier<ChicoryProcess> context, boolean kernelStore) {
        this.host = host; this.privileged = privileged; this.context = context; this.kernelStore = kernelStore;
    }
    private ChicoryProcess process() { return context.get(); }
    ImportValues imports() {
        var imports = ImportValues.builder();
        imports.addFunction(new HostFunction("hal", "hal_yield", FunctionType.empty(), (instance, args) -> {
            process().yieldProcess(); return new long[0];
        }));
        add(imports, "hal_restart", 0, (instance, args) -> {
            process().charge(1);
            if (!privileged) return -1;
            host.processes().restart();
            throw new ChicoryProcess.Restart();
        });
        if (!kernelStore) imports.addFunction(new HostFunction("sys", "syscall",
                FunctionType.of(Collections.nCopies(5, ValType.I32), List.of(ValType.I32)), (instance, args) -> {
            try { return new long[] { syscall(instance, args) }; }
            catch (MemoryFault fault) { return new long[] { fault.code }; }
        }));
        if (!privileged) return imports.build();
        if (!kernelStore) add(imports, "hal_install_kernel", 2, this::installKernel);
        imports.addFunction(new HostFunction("hal", "hal_wake", FunctionType.of(List.of(ValType.I64), List.of(ValType.I32)), (instance, args) -> {
            process().charge(1);
            return new long[] { args[0] > 0 && host.processes().wake(args[0]) ? 0 : -3 };
        }));
        addMetric(imports, "hal_cpu_time_ns", host.metrics()::cpuTimeNanos);
        addMetric(imports, "hal_ram_used_pages", host.metrics()::ramUsedPages);
        add(imports, "hal_device_count", 0, (instance, args) -> host.devices().size());
        add(imports, "hal_device_info", 3, this::deviceInfo);
        add(imports, "hal_device_call", 6, this::deviceCall);
        add(imports, "hal_poll_event", 2, this::pollEvent);
        add(imports, "hal_chat_print", 2, this::chatPrint);
        addSpawn(imports, "hal_spawn_process", false);
        addSpawn(imports, "hal_spawn_process_privileged", true);
        imports.addFunction(new HostFunction("hal", "hal_kill_process",
                FunctionType.of(List.of(ValType.I64), List.of(ValType.I32)), (instance, args) -> {
            process().charge(2);
            return new long[] { host.processes().kill(args[0]) ? 0 : -1 };
        }));
        imports.addFunction(new HostFunction("hal", "increment", FunctionType.empty(), (instance, args) -> {
            process().charge(1); return new long[0];
        }));
        return imports.build();
    }
    private int installKernel(Instance instance, long[] args) {
        int pointer = (int) args[0], length = (int) args[1];
        if (length < 8 || length > 1024 * 1024) return -3;
        Memory memory = memory(instance); range(memory, pointer, length);
        byte[] bytes = memory.readBytes(pointer, length);
        process().charge(8L + (length + 16383) / 16384);
        return process().computer == null ? -16 : process().computer.install(bytes, process());
    }
    private int syscall(Instance instance, long[] args) {
        int number = (int) args[0], input = (int) args[1], length = (int) args[2];
        int output = (int) args[3], capacity = (int) args[4];
        if (input < 0 || length < 0 || output < 0 || capacity < 0) return -3;
        if ((long) length + capacity > MAX_CALL) return -11;
        Memory memory = memory(instance); range(memory, input, length); range(memory, output, capacity);
        byte[] request = memory.readBytes(input, length);
        process().charge(8L + ((long) length + capacity + 255) / 256);
        return process().computer == null ? -13 : process().computer.syscall(process(), number, request, memory, output, capacity);
    }
    private void addMetric(ImportValues.Builder builder, String name, java.util.function.LongSupplier value) {
        builder.addFunction(new HostFunction("hal", name, FunctionType.of(List.of(), List.of(ValType.I64)), (instance, args) -> {
            process().charge(1);
            return new long[] { value.getAsLong() };
        }));
    }
    private void addSpawn(ImportValues.Builder builder, String name, boolean childPrivileged) {
        builder.addFunction(new HostFunction("hal", name, FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I64)), (instance, args) -> {
            int pointer = (int) args[0], length = (int) args[1];
            if (length < 8 || length > 1024 * 1024) return new long[] { 0 };
            try {
                Memory memory = memory(instance); range(memory, pointer, length);
                process().charge(8L + (length + 16383) / 16384);
                return new long[] { host.processes().spawn(memory.readBytes(pointer, length), childPrivileged) };
            } catch (MemoryFault invalid) { return new long[] { 0 }; }
        }));
    }
    @FunctionalInterface private interface Call { int call(Instance instance, long[] args); }
    private void add(ImportValues.Builder builder, String name, int arity, Call call) {
        builder.addFunction(new HostFunction("hal", name,
                FunctionType.of(Collections.nCopies(arity, ValType.I32), List.of(ValType.I32)), (instance, args) -> {
            try { return new long[] { call.call(instance, args) }; }
            catch (MemoryFault fault) { return new long[] { fault.code }; }
        }));
    }
    private int deviceInfo(Instance instance, long[] args) {
        int index = (int) args[0], output = (int) args[1], capacity = (int) args[2];
        var devices = host.devices().enumerate();
        if (index < 0 || output < 0 || index >= devices.size()) return -3;
        if (capacity < 20) return -5;
        Memory memory = memory(instance);
        try { range(memory, output, 20); } catch (MemoryFault fault) { return -9; }
        process().charge(1);
        var descriptor = devices.get(index);
        ByteBuffer bytes = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(descriptor.interfaceId().numericId()).order(ByteOrder.BIG_ENDIAN);
        bytes.putLong(descriptor.id().getMostSignificantBits()).putLong(descriptor.id().getLeastSignificantBits());
        memory.write(output, bytes.array());
        return 0;
    }
    private int deviceCall(Instance instance, long[] args) {
        int uuidPointer = (int) args[0], operation = (int) args[1], pointer = (int) args[2], length = (int) args[3];
        int output = (int) args[4], capacity = (int) args[5];
        if (length < 0 || capacity < 0) return -3;
        if ((long) length + capacity > MAX_CALL) return -11;
        Memory memory = memory(instance);
        range(memory, uuidPointer, 16); range(memory, pointer, length); range(memory, output, capacity);
        process().charge(8L + ((long) length + capacity + 255) / 256);
        ByteBuffer uuid = ByteBuffer.wrap(memory.readBytes(uuidPointer, 16));
        UUID id = new UUID(uuid.getLong(), uuid.getLong());
        DeviceResult result = host.devices().device_call(id, new DeviceCall(operation, memory.readBytes(pointer, length), capacity));
        if (result.code() < 0) return result.code();
        byte[] response = result.response();
        memory.write(output, response);
        return result.code();
    }
    private int pollEvent(Instance instance, long[] args) {
        int pointer = (int) args[0], capacity = (int) args[1];
        if (pointer < 0 || capacity < 0) return -2;
        if (capacity > MAX_CALL) return -11;
        Memory memory;
        try { memory = memory(instance); range(memory, pointer, capacity); }
        catch (MemoryFault fault) { return -3; }
        process().charge(1L + ((long) capacity + 255) / 256);
        return host.mailbox().pollPayload(capacity).map(bytes -> {
            if (bytes.length > capacity) return -2;
            memory.write(pointer, bytes); return bytes.length;
        }).orElse(-1);
    }
    private int chatPrint(Instance instance, long[] args) {
        int pointer = (int) args[0], length = (int) args[1];
        if (length < 0 || length > 2048) return -3;
        Memory memory = memory(instance); range(memory, pointer, length);
        process().charge(1L + (length + 255) / 256);
        return host.chat().test(new String(memory.readBytes(pointer, length), StandardCharsets.UTF_8)) ? 0 : -11;
    }
    private static Memory memory(Instance instance) {
        try { return instance.exports().memory("memory"); }
        catch (RuntimeException missing) { throw new MemoryFault(-9); }
    }
    private static void range(Memory memory, int pointer, int length) {
        if (pointer < 0 || length < 0 || (long) pointer + length > (long) memory.pages() * Memory.PAGE_SIZE)
            throw new MemoryFault(-8);
    }
    private static final class MemoryFault extends RuntimeException {
        private final int code;
        MemoryFault(int code) { super(null, null, false, false); this.code = code; }
    }
}
