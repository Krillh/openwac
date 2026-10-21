package org.mod.openwac.core.machine;

import org.mod.openwac.core.device.DeviceSnapshot;
import java.util.List;
import java.util.UUID;

public record MachineSnapshot(int schemaVersion, UUID machineId, MachineConfig config,
                              MachineState savedState, UUID bootMedia,
                              List<DeviceSnapshot> devices, long logicalTick) {
    public MachineSnapshot { devices = List.copyOf(devices); }
}
