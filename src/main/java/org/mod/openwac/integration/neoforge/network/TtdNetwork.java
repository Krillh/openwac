package org.mod.openwac.integration.neoforge.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;
import java.util.*;

public final class TtdNetwork {
    // Weak keys avoid retaining disconnected players or old server instances.
    private static final Map<ServerPlayer, Long> REQUEST_TIMES = new WeakHashMap<>();
    private TtdNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(TtdPayloads.Open.TYPE, TtdPayloads.Open.CODEC);
        registrar.playToClient(TtdPayloads.Update.TYPE, TtdPayloads.Update.CODEC);
        registrar.playToClient(TtdPayloads.Power.TYPE, TtdPayloads.Power.CODEC);
        registrar.playToServer(TtdPayloads.Request.TYPE, TtdPayloads.Request.CODEC, (request, context) -> {
            ServerPlayer player = (ServerPlayer)context.player();
            long now = System.nanoTime();
            if (now - REQUEST_TIMES.getOrDefault(player, 0L) < 200_000_000L) return;
            REQUEST_TIMES.put(player, now);
            var level = player.level();
            if (player.blockPosition().distSqr(request.pos()) > 4096 || !level.hasChunkAt(request.pos())) return;
            if (level.getBlockEntity(request.pos()) instanceof TtdBlockEntity display && display.device().id().equals(request.device()))
                PacketDistributor.sendToPlayer(player, TtdPayloads.Update.full(request.pos(), request.device(), display.buffer().snapshot()),
                        new TtdPayloads.Power(request.pos(), request.device(), display.isPowered()));
        });
    }
}
