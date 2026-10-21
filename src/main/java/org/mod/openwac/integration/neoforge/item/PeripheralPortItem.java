package org.mod.openwac.integration.neoforge.item;

import net.minecraft.world.item.ItemStack;
import org.mod.openwac.core.device.*;

public final class PeripheralPortItem extends ComponentItem {
    public PeripheralPortItem(Properties properties) { super(properties); }
    @Override public Device createDevice(ItemStack stack, ComponentContext context) { return new PeripheralPortDevice(deviceId(stack)); }
}
