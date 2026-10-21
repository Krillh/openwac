package org.mod.openwac.integration.neoforge.blockentity;

import com.mojang.serialization.Codec;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.mod.openwac.core.device.display.*;
import org.mod.openwac.integration.neoforge.item.TtdBlockItem;
import org.mod.openwac.integration.neoforge.network.TtdPayloads;
import org.mod.openwac.integration.neoforge.registration.ModContent;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class TtdBlockEntity extends BlockEntity implements org.mod.openwac.integration.neoforge.peripheral.PeripheralEndpoint {
    private final TtdBuffer buffer = new TtdBuffer();
    private TtdDevice device = new TtdDevice(UUID.randomUUID(), buffer);
    private long lastSnapshotRequest;
    private boolean needsSnapshot;
    private volatile boolean clientPowered;
    private boolean lastSentPower;
    public TtdBlockEntity(BlockPos pos, BlockState state) { super(ModContent.TTD_ENTITY.get(), pos, state); }
    public TtdBuffer buffer() { return buffer; }
    public TtdDevice device() { return device; }
    public boolean isPowered() { return level != null && level.isClientSide() ? clientPowered : device.isPowered(); }
    public void applyClientPower(boolean powered) { if (level != null && level.isClientSide()) clientPowered = powered; }
    @Override public UUID peripheralId() { return device.id(); }
    @Override public java.util.Optional<org.mod.openwac.core.device.Device> claimPeripheral(UUID computer) { return device.claim(computer); }
    @Override public boolean ownedBy(UUID computer) { return device.ownedBy(computer); }
    @Override public void releasePeripheral(UUID computer) { device.release(computer); }
    public boolean needsSnapshot() { return needsSnapshot; }
    public void needsSnapshot(boolean value) { needsSnapshot = value; }
    public boolean allowSnapshotRequest() {
        long now = System.nanoTime();
        if (now - lastSnapshotRequest < 1_000_000_000L) return false;
        lastSnapshotRequest = now; return true;
    }
    public void restoreItem(ItemStack stack) {
        TtdBlockItem.deviceId(stack);
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        loadAdditional(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, TtdBlockEntity display) {
        boolean powered = display.device.isPowered();
        if (powered != display.lastSentPower) {
            display.lastSentPower = powered;
            PacketDistributor.sendToPlayersTrackingChunk((net.minecraft.server.level.ServerLevel)level,
                    new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4),
                    new TtdPayloads.Power(pos, display.device.id(), powered));
        }
        TtdBuffer.Delta delta = display.buffer.drain();
        if (delta == null) return;
        display.setChanged();
        PacketDistributor.sendToPlayersTrackingChunk((net.minecraft.server.level.ServerLevel)level, new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4),
                TtdPayloads.Update.delta(pos, display.device.id(), delta));
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        var saved = buffer.snapshot();
        output.putInt("TtdVersion", 1);
        output.putString("DeviceId", device.id().toString());
        output.store("Cells", Codec.BYTE_BUFFER, ByteBuffer.wrap(saved.cells()));
        output.putLong("Revision", saved.revision());
        output.putInt("CursorX", saved.cursor().x()); output.putInt("CursorY", saved.cursor().y());
        output.putBoolean("CursorVisible", saved.cursor().visible()); output.putBoolean("CursorBlink", saved.cursor().blink());
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        UUID id;
        try { id = UUID.fromString(input.getStringOr("DeviceId", "")); }
        catch (IllegalArgumentException invalid) { id = UUID.randomUUID(); }
        device.invalidate(); device = new TtdDevice(id, buffer);
        clientPowered = input.getBooleanOr("DisplayPowered", false);
        byte[] cells = new byte[TtdBuffer.CELLS]; java.util.Arrays.fill(cells, (byte)' ');
        input.read("Cells", Codec.BYTE_BUFFER).ifPresent(bytes -> {
            if (bytes.remaining() == cells.length) bytes.duplicate().get(cells);
        });
        int x = input.getIntOr("CursorX", 0), y = input.getIntOr("CursorY", 0);
        if (!TtdBuffer.fits(x, y, 1, 1)) { x = 0; y = 0; }
        buffer.restore(new TtdBuffer.Snapshot(Math.max(0, input.getLongOr("Revision", 0)), cells,
                new TtdBuffer.Cursor(x, y, input.getBooleanOr("CursorVisible", false), input.getBooleanOr("CursorBlink", false))));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        // Runtime supply is sent to clients, never restored as powered from world/item save data.
        tag.putBoolean("DisplayPowered", device.isPowered());
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void setRemoved() { device.invalidate(); super.setRemoved(); }
    @Override public void clearRemoved() {
        super.clearRemoved();
        device.invalidate(); device = new TtdDevice(device.id(), buffer);
    }
    @Override public void onChunkUnloaded() { device.invalidate(); super.onChunkUnloaded(); }
}
