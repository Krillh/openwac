package org.mod.openwac.integration.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.mod.openwac.integration.neoforge.storage.DiskLifecycle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.function.Predicate;

@Mixin(Inventory.class)
abstract class ClearDiskDeletionMixin {
    @Shadow @Final public Player player;

    @WrapMethod(method = "clearOrCountMatchingItems")
    private int openwac$clearDisks(Predicate<ItemStack> predicate, boolean countingOnly, int amount,
                                 Container craftSlots, Operation<Integer> original) {
        if (countingOnly || !(player instanceof ServerPlayer serverPlayer))
            return original.call(predicate, countingOnly, amount, craftSlots);
        var previous = DiskLifecycle.disks((Inventory) (Object) this);
        previous.addAll(DiskLifecycle.disks(craftSlots));
        DiskLifecycle.diskId(player.containerMenu.getCarried()).ifPresent(previous::add);
        int removed = original.call(predicate, countingOnly, amount, craftSlots);
        if (removed > 0) previous.forEach(id -> DiskLifecycle.removed(serverPlayer, id));
        return removed;
    }
}
