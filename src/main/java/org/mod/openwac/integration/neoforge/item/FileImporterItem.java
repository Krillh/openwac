package org.mod.openwac.integration.neoforge.item;

import net.minecraft.world.item.ItemStack;
import org.mod.openwac.core.device.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import org.mod.openwac.integration.neoforge.network.ImporterUploads;

/** Component identity persists in item data; the host selects the file through its repository. */
public final class FileImporterItem extends ComponentItem {
    public FileImporterItem(Properties properties) { super(properties); }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer)
            ImporterUploads.get(serverPlayer).open(serverPlayer, deviceId(player.getItemInHand(hand)));
        return InteractionResult.SUCCESS;
    }
    @Override public Device createDevice(ItemStack stack, ComponentContext context) {
        return context.importers().open(deviceId(stack));
    }
}
