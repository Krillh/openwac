package org.mod.openwac.integration.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.mod.openwac.integration.neoforge.storage.DiskLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CreativeDiskDeletionMixin {
    @Shadow public ServerPlayer player;

    @WrapOperation(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;setByPlayer(Lnet/minecraft/world/item/ItemStack;)V"))
    private void openwac$replaceCreativeSlot(Slot slot, ItemStack replacement, Operation<Void> original) {
        var previous = DiskLifecycle.diskId(slot.getItem());
        original.call(slot, replacement);
        previous.ifPresent(id -> DiskLifecycle.removed(player, id));
    }
}
