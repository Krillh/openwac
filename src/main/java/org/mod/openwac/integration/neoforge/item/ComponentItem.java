package org.mod.openwac.integration.neoforge.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.mod.openwac.core.device.Device;
import org.mod.openwac.core.device.ComponentContext;
import java.util.UUID;
import java.util.Optional;

/** One physical component per stack. UUIDs are persisted in item data, never derived from a slot. */
public abstract class ComponentItem extends Item {
    private static final String ID_KEY = "openwac_device_uuid";
    protected ComponentItem(Properties properties) { super(properties.stacksTo(1)); }
    public static Optional<UUID> existingDeviceId(ItemStack stack) {
        String stored = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(ID_KEY, "");
        try { return Optional.of(UUID.fromString(stored)); }
        catch (IllegalArgumentException absentOrInvalid) { return Optional.empty(); }
    }
    public static UUID deviceId(ItemStack stack) {
        return existingDeviceId(stack).orElseGet(() -> {
            UUID created = UUID.randomUUID();
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(ID_KEY, created.toString()));
            return created;
        });
    }
    @Override public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) { deviceId(stack); }
    @Override public void onCraftedBy(ItemStack stack, net.minecraft.world.entity.player.Player player) {
        super.onCraftedBy(stack, player);
        if (!player.level().isClientSide()) deviceId(stack);
    }
    public abstract Device createDevice(ItemStack stack, ComponentContext context);
}
