package org.mod.openwac.integration.neoforge.item;

import net.minecraft.world.item.ItemStack;
import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.storage.DiskDevice;

public final class DiskDriveItem extends ComponentItem {
    public static final int BLOCK_SIZE = 2048, BLOCK_COUNT = 512;
    public DiskDriveItem(Properties properties) { super(properties); }
    @Override public Device createDevice(ItemStack stack, ComponentContext context) {
        // Called during powered computer startup; even this handle is lazy until guest media access.
        return new DiskDevice(context.storage().open(deviceId(stack), BLOCK_SIZE, BLOCK_COUNT));
    }
}
