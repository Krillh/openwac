package org.mod.openwac.integration.neoforge.item;

import net.minecraft.world.item.Item;
import org.mod.openwac.core.machine.MachineConfig;

/** Supplies immutable execution limits when the computer starts. */
public final class CpuItem extends Item {
    private final long fuelPerTick;
    private final int maxProcesses;

    public CpuItem(Properties properties, long fuelPerTick, int maxProcesses) {
        super(properties.stacksTo(16));
        new MachineConfig(fuelPerTick, 256, maxProcesses, 65_536);
        this.fuelPerTick = fuelPerTick;
        this.maxProcesses = maxProcesses;
    }

    public MachineConfig machineConfig(long installedMemoryBytes) {
        return new MachineConfig(fuelPerTick, 256, maxProcesses, installedMemoryBytes);
    }
}
