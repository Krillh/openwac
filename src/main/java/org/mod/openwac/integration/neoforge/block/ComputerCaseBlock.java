package org.mod.openwac.integration.neoforge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import org.mod.openwac.integration.neoforge.blockentity.ComputerCaseBlockEntity;
import org.mod.openwac.integration.neoforge.registration.ModContent;

public final class ComputerCaseBlock extends BaseEntityBlock {
    public ComputerCaseBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ComputerCaseBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModContent.COMPUTER_CASE_ENTITY.get(), ComputerCaseBlockEntity::serverTick);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof ComputerCaseBlockEntity computer
                && !player.isSpectator() && computer.canOpen(player)) {
            if (player.isShiftKeyDown()) computer.togglePower(player);
            else player.openMenu(computer);
        }
        return InteractionResult.SUCCESS;
    }
}
