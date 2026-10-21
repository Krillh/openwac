package org.mod.openwac.integration.neoforge.item;

import net.minecraft.world.item.Item;
import org.mod.openwac.core.machine.MemoryBudget;

/** Physical RAM capacity; modules do not expose a guest device interface. */
public final class RamItem extends Item {
    private final long capacityBytes;

    public RamItem(Properties properties, long capacityBytes) {
        super(properties.stacksTo(16));
        new MemoryBudget(capacityBytes); // Validate page alignment and supported capacity.
        this.capacityBytes = capacityBytes;
    }

    public long capacityBytes() { return capacityBytes; }
}
