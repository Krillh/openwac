package org.mod.openwac.runtime;

import org.mod.openwac.core.process.WasmProcess;
import org.mod.openwac.core.machine.MemoryBudget;
import org.mod.openwac.runtime.hal.HostServices;

/** Runtime boundary; only runtime implementations may depend on a Wasm library. */
public interface WasmEngine {
    CompiledModule compile(byte[] wasm, EngineLimits limits);
    WasmProcess createProcess(byte[] wasm, EngineLimits limits, MemoryBudget memory, HostServices host, boolean privileged);
    WasmComputer createComputer(EngineLimits limits, MemoryBudget memory, HostServices host, int processLimit, java.util.function.Consumer<String> panic);
    interface CompiledModule extends AutoCloseable { @Override void close(); }
}
