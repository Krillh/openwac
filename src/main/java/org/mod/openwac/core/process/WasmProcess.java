package org.mod.openwac.core.process;

/** A resumable process. Implementations must bound each turn and preserve the continuation. */
public interface WasmProcess extends AutoCloseable {
    record Step(long fuelUsed, Reason reason, String diagnostic) {
        public Step {
            if (fuelUsed < 0) throw new IllegalArgumentException("Negative fuel consumption");
        }
    }
    enum Reason { QUANTUM_EXHAUSTED, YIELDED, BLOCKED, NEXT_TICK, KERNEL_WAIT, KILLED, EXITED, TRAPPED }
    Step runQuantum(long fuel);
    void wake();
    default org.mod.openwac.core.diagnostic.FailureDetails failureDetails() { return null; }
    default void requestKill() {}
    default boolean inKernel() { return false; }
    @Override void close();
}
