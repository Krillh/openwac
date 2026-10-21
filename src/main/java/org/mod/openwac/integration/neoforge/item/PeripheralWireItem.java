package org.mod.openwac.integration.neoforge.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.mod.openwac.integration.neoforge.blockentity.ComputerCaseBlockEntity;
import org.mod.openwac.integration.neoforge.peripheral.*;
import java.util.UUID;

/** Reusable two-click logical wire. Only validated server-side block identities form links. */
public final class PeripheralWireItem extends Item {
    private static final String SELECTION = "openwac_wire_selection";
    public PeripheralWireItem(Properties properties) { super(properties.stacksTo(1)); }
    // Run before a case or screen consumes right-click to open its own UI.
    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) { return useOn(context); }
    private record Endpoint(BlockPos pos, UUID id, boolean computer) {}
    private static Endpoint endpoint(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var entity = level.getBlockEntity(pos);
        if (entity instanceof ComputerCaseBlockEntity computer) return new Endpoint(pos.immutable(), computer.machineId(), true);
        if (entity instanceof PeripheralEndpoint peripheral) return new Endpoint(pos.immutable(), peripheral.peripheralId(), false);
        return null;
    }
    private static void clear(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(SELECTION));
    }
    private static void message(Player player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable("message.openwac.wire." + key, args));
    }
    private static void select(ItemStack stack, ServerLevel level, Player player, Endpoint endpoint) {
        var selection = new net.minecraft.nbt.CompoundTag();
        selection.putString("Dimension", level.dimension().identifier().toString());
        selection.putString("Player", player.getUUID().toString());
        selection.putString("DeviceId", endpoint.id().toString());
        selection.putInt("X", endpoint.pos().getX()); selection.putInt("Y", endpoint.pos().getY()); selection.putInt("Z", endpoint.pos().getZ());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(SELECTION, selection));
        message(player, "selected", endpoint.computer() ? "computer" : "peripheral");
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        if (!player.mayBuild() || player.isSpectator() || !level.mayInteract(player, clicked)) {
            message(player, "denied"); return InteractionResult.SUCCESS;
        }
        Endpoint current = endpoint(level, clicked);
        if (current == null) { message(player, "invalid"); return InteractionResult.SUCCESS; }
        if (current.computer() && !((ComputerCaseBlockEntity)level.getBlockEntity(clicked)).canOpen(player)) {
            message(player, "denied"); return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown() && current.computer()) {
            int count = ((ComputerCaseBlockEntity)level.getBlockEntity(clicked)).clearPeripheralLinks();
            clear(stack); message(player, "cleared", count); return InteractionResult.SUCCESS;
        }
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var stored = data.getCompound(SELECTION).orElse(null);
        if (stored == null) { select(stack, level, player, current); return InteractionResult.SUCCESS; }
        if (!stored.getStringOr("Player", "").equals(player.getUUID().toString())
                || !stored.getStringOr("Dimension", "").equals(level.dimension().identifier().toString())) {
            clear(stack); message(player, "expired"); return InteractionResult.SUCCESS;
        }
        BlockPos firstPos = new BlockPos(stored.getIntOr("X", 0), stored.getIntOr("Y", 0), stored.getIntOr("Z", 0));
        if (!PeripheralConnections.inRange(firstPos, clicked)) {
            message(player, "too_far", PeripheralConnections.MAX_DISTANCE); return InteractionResult.SUCCESS;
        }
        Endpoint first = endpoint(level, firstPos);
        if (first == null || !first.id().toString().equals(stored.getStringOr("DeviceId", ""))) {
            clear(stack); message(player, "expired"); return InteractionResult.SUCCESS;
        }
        if (first.computer() == current.computer()) {
            select(stack, level, player, current); message(player, "different_kinds"); return InteractionResult.SUCCESS;
        }
        if (!level.mayInteract(player, firstPos)) { clear(stack); message(player, "denied"); return InteractionResult.SUCCESS; }
        Endpoint computerEndpoint = first.computer() ? first : current;
        Endpoint peripheralEndpoint = first.computer() ? current : first;
        var computer = (ComputerCaseBlockEntity)level.getBlockEntity(computerEndpoint.pos());
        if (!computer.canOpen(player)) { clear(stack); message(player, "denied"); return InteractionResult.SUCCESS; }
        try {
            boolean added = computer.togglePeripheralLink(peripheralEndpoint.pos(), peripheralEndpoint.id());
            clear(stack); message(player, added ? "linked" : "unlinked");
        } catch (IllegalStateException full) { message(player, "full", PeripheralConnections.MAX_LINKS); }
        return InteractionResult.SUCCESS;
    }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            if (player.isShiftKeyDown()) { clear(player.getItemInHand(hand)); message(player, "cancelled"); }
            else message(player, "instructions");
        }
        return InteractionResult.SUCCESS;
    }
}
