package org.mod.openwac.runtime.hal;

import org.mod.openwac.core.device.DeviceBus;
import org.mod.openwac.core.event.EventHub;
import java.util.Objects;
import java.util.function.Predicate;

/** Platform-neutral services exposed to a process. Output is queued by the integration layer. */
public record HostServices(DeviceBus devices, EventHub mailbox, Predicate<String> chat, ProcessControl processes, MachineMetrics metrics) {
    /** Read-only measurements shared by all processes belonging to one computer. */
    public interface MachineMetrics {
        long cpuTimeNanos();
        long ramUsedPages();
    }
    public interface ProcessControl {
        long spawn(byte[] wasm, boolean privileged);
        boolean kill(long id);
        default boolean wake(long id) { return false; }
        default void resume(long id) {}
        /** Requests a full machine power cycle after the current execution turn. */
        default void restart() { throw new UnsupportedOperationException("Computer restart is unavailable"); }
    }
    public HostServices {
        Objects.requireNonNull(devices);
        Objects.requireNonNull(mailbox);
        Objects.requireNonNull(chat);
        Objects.requireNonNull(processes);
        Objects.requireNonNull(metrics);
    }
}
