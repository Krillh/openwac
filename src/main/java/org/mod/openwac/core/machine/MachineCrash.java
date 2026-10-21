package org.mod.openwac.core.machine;

import org.mod.openwac.core.diagnostic.FailureDetails;
import java.util.UUID;

/** Immutable evidence captured before the machine publishes its fatal state. Runtime-only. */
public record MachineCrash(UUID machineId, long tick, long processId, String firmware,
                           MachineConfig config, FailureDetails failure) {
    public MachineCrash { firmware = FailureDetails.limit(java.util.Objects.toString(firmware, "unknown"), 512); }
}
