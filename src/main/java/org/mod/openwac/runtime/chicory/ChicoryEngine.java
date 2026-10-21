package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.*;
import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.core.machine.MemoryBudget;
import org.mod.openwac.runtime.EngineLimits;
import org.mod.openwac.runtime.WasmEngine;
import org.mod.openwac.runtime.hal.HostServices;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

/** Interpreter only: execution listeners provide instruction metering and cooperative parking. */
public final class ChicoryEngine implements WasmEngine {
    @Override public CompiledModule compile(byte[] wasm, EngineLimits limits) {
        if (wasm == null || wasm.length < 8 || wasm.length > limits.maxModuleBytes()) throw new IllegalArgumentException("WASM module size outside configured limits");
        byte[] bytes = Arrays.copyOf(wasm, wasm.length);
        WasmPreflight.validate(bytes);
        WasmModule module = Parser.parse(new ByteArrayInputStream(bytes));
        validateLimits(module, limits);
        return new ParsedModule(module);
    }
    @Override public WasmProcess createProcess(byte[] wasm, EngineLimits limits, MemoryBudget memory, HostServices host, boolean privileged) {
        return new ChicoryProcess(this, wasm.clone(), limits, java.util.Objects.requireNonNull(memory), host, privileged);
    }
    @Override public org.mod.openwac.runtime.WasmComputer createComputer(EngineLimits limits, MemoryBudget memory,
            HostServices host, int processLimit, java.util.function.Consumer<String> panic) {
        return new ChicoryComputer(this, limits, memory, host, processLimit, panic);
    }
    private void validateLimits(WasmModule module, EngineLimits limits) {
        if (module.functionSection().functionCount() > 4096 || module.typeSection().subTypeCount() > 4096
                || module.globalSection().globalCount() > 4096 || module.tableSection().tableCount() > 1
                || module.importSection().importCount() > 64)
            throw new IllegalArgumentException("Module structure exceeds limits");
        if (module.tagSection().isPresent()) throw new IllegalArgumentException("Exception tags are unsupported");
        for (int i = 0; i < module.typeSection().typeCount(); i++)
            if (!module.typeSection().getRecType(i).isLegacy()) throw new IllegalArgumentException("GC types are unsupported");
        for (var function : module.codeSection().functionBodies()) {
            if (function.localTypes().size() > 4096) throw new IllegalArgumentException("Too many locals");
            for (var instruction : function.instructions()) {
                String opcode = instruction.opcode().name();
                if (opcode.toUpperCase(java.util.Locale.ROOT).matches("^(V128|I8X16|I16X8|I32X4|I64X2|F32X4|F64X2)_.*")
                        || opcode.contains("ATOMIC") || opcode.startsWith("ARRAY") || opcode.startsWith("STRUCT")
                        || opcode.startsWith("I31") || opcode.startsWith("REF_I31") || opcode.startsWith("ANY_")
                        || opcode.startsWith("EXTERN_") || opcode.startsWith("REF_CAST") || opcode.startsWith("REF_TEST")
                        || opcode.startsWith("BR_ON_") || opcode.startsWith("THROW") || opcode.startsWith("TRY"))
                    throw new IllegalArgumentException("Unsupported instruction: " + opcode);
            }
        }
        int memories = module.memorySection().map(MemorySection::memoryCount).orElse(0);
        if (memories > 1) throw new IllegalArgumentException("Multiple memories are unsupported");
        if (memories == 1) {
            var memory = module.memorySection().orElseThrow().getMemory(0).limits();
            if (memory.shared()) throw new IllegalArgumentException("Shared WASM memory is unsupported");
            if (memory.initialPages() * 65536L > limits.maxMemoryBytes())
                throw new IllegalArgumentException("Initial WASM memory requests " + memory.initialPages()
                        + " pages; computer limit is " + limits.maxMemoryBytes() / 65536 + " pages");
        }
        for (int i = 0; i < module.tableSection().tableCount(); i++) {
            var table = module.tableSection().getTable(i);
            if (table.limits().shared() || table.limits().min() > 4096 || table.limits().max() > 4096)
                throw new IllegalArgumentException("Tables require an explicit maximum of at most 4096 entries");
        }
        for (int i = 0; i < module.importSection().importCount(); i++)
            if (module.importSection().getImport(i).importType() != ExternalType.FUNCTION)
                throw new IllegalArgumentException("Only function imports are supported");
    }
    record ParsedModule(WasmModule module) implements CompiledModule {
        @Override public void close() {}
    }
}
