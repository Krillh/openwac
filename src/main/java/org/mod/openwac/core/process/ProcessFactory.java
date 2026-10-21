package org.mod.openwac.core.process;

@FunctionalInterface
public interface ProcessFactory {
    WasmProcess create(long processId, byte[] wasm, boolean privileged);
}
