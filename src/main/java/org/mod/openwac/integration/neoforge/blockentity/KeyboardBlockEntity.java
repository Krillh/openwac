package org.mod.openwac.integration.neoforge.blockentity;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import org.mod.openwac.core.device.Device;
import org.mod.openwac.core.device.input.KeyboardDevice;
import org.mod.openwac.integration.neoforge.item.KeyboardBlockItem;
import org.mod.openwac.integration.neoforge.peripheral.PeripheralEndpoint;
import org.mod.openwac.integration.neoforge.registration.ModContent;
import java.util.*;

/** Persist physical identity only. Sessions, held keys and queued input are transient. */
public final class KeyboardBlockEntity extends BlockEntity implements PeripheralEndpoint {
    private KeyboardDevice device = new KeyboardDevice(UUID.randomUUID());
    public KeyboardBlockEntity(BlockPos pos, BlockState state) { super(ModContent.KEYBOARD_ENTITY.get(), pos, state); }
    public KeyboardDevice device() { return device; }
    @Override public UUID peripheralId() { return device.id(); }
    @Override public Optional<Device> claimPeripheral(UUID computer) { return device.claim(computer); }
    @Override public boolean ownedBy(UUID computer) { return device.ownedBy(computer); }
    @Override public void releasePeripheral(UUID computer) { device.release(computer); }
    public void restoreItem(ItemStack stack) {
        UUID id = KeyboardBlockItem.deviceId(stack);
        device.invalidate(); device = new KeyboardDevice(id); setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output); output.putInt("KeyboardVersion", 1); output.putString("DeviceId", device.id().toString());
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); UUID id;
        try { id = UUID.fromString(input.getStringOr("DeviceId", "")); }
        catch (IllegalArgumentException invalid) { id = UUID.randomUUID(); }
        device.invalidate(); device = new KeyboardDevice(id);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void clearRemoved() { super.clearRemoved(); device.invalidate(); device = new KeyboardDevice(device.id()); }
    @Override public void setRemoved() { device.invalidate(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { device.invalidate(); super.onChunkUnloaded(); }
}
