package org.mod.openwac.runtime;

import org.mod.openwac.core.process.*;

/** One machine's passive kernel and process execution session. */
public interface WasmComputer extends AutoCloseable, ProcessManager.Lifecycle {
    WasmProcess createProcess(long id, byte[] wasm, boolean privileged);
    default org.mod.openwac.core.diagnostic.FailureDetails kernelFailure() { return null; }
    default void onKernelInstallFailure(java.util.function.Consumer<KernelInstallFailure> listener) {}
    @Override void close();
}
