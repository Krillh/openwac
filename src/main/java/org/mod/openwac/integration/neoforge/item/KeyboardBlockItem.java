package org.mod.openwac.integration.neoforge.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import java.util.UUID;

public final class KeyboardBlockItem extends BlockItem {
    public KeyboardBlockItem(Block block, Properties properties) { super(block, properties.stacksTo(1)); }
    public static UUID deviceId(ItemStack stack) {
        String stored = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("DeviceId", "");
        try { return UUID.fromString(stored); }
        catch (IllegalArgumentException invalid) {
            UUID id = UUID.randomUUID();
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("DeviceId", id.toString()));
            return id;
        }
    }
    @Override public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) { deviceId(stack); }
}
