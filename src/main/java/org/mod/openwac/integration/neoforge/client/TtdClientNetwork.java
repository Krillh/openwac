package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.mod.openwac.core.device.display.TtdBuffer;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;
import org.mod.openwac.integration.neoforge.network.TtdPayloads;

public final class TtdClientNetwork {
    private TtdClientNetwork() {}
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(TtdPayloads.Power.TYPE, (power, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null && client.level.getBlockEntity(power.pos()) instanceof TtdBlockEntity display
                    && display.device().id().equals(power.device())) display.applyClientPower(power.powered());
        });
        event.register(TtdPayloads.Open.TYPE, (open, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null || client.player.blockPosition().distSqr(open.pos()) > 64) return;
            if (client.level.getBlockEntity(open.pos()) instanceof TtdBlockEntity display && display.device().id().equals(open.device())) {
                display.needsSnapshot(true); ensureSnapshot(display);
                client.gui.setScreen(new TtdScreen(open.pos(), open.device()));
            }
        });
        event.register(TtdPayloads.Update.TYPE, (update, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || !(client.level.getBlockEntity(update.pos()) instanceof TtdBlockEntity display)
                    || !display.device().id().equals(update.device())) return;
            long current = display.buffer().snapshot().revision();
            if (update.base() == -1) {
                if (update.revision() < current) return;
                byte[] cells = new byte[TtdBuffer.CELLS];
                for (var span : update.spans()) System.arraycopy(span.cells(), 0, cells, span.y() * TtdBuffer.WIDTH + span.x(), span.cells().length);
                display.buffer().restore(new TtdBuffer.Snapshot(update.revision(), cells, update.cursor()));
                display.needsSnapshot(false);
            } else if (update.revision() > current && !display.buffer().apply(new TtdBuffer.Delta(update.base(), update.revision(), update.spans(), update.cursor()))) {
                display.needsSnapshot(true); ensureSnapshot(display);
            }
        });
    }
    public static void ensureSnapshot(TtdBlockEntity display) {
        if (display.needsSnapshot() && display.allowSnapshotRequest() && Minecraft.getInstance().getConnection() != null)
            ClientPacketDistributor.sendToServer(new TtdPayloads.Request(display.getBlockPos(), display.device().id()));
    }
}
