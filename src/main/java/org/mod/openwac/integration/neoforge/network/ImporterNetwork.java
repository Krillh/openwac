package org.mod.openwac.integration.neoforge.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ImporterNetwork {
    private ImporterNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(ImporterPayloads.Open.TYPE, ImporterPayloads.Open.CODEC);
        registrar.playToClient(ImporterPayloads.Reply.TYPE, ImporterPayloads.Reply.CODEC);
        registrar.playToServer(ImporterPayloads.Begin.TYPE, ImporterPayloads.Begin.CODEC,
                (payload, context) -> ImporterUploads.get((ServerPlayer) context.player()).begin((ServerPlayer) context.player(), payload));
        registrar.playToServer(ImporterPayloads.Chunk.TYPE, ImporterPayloads.Chunk.CODEC,
                (payload, context) -> ImporterUploads.get((ServerPlayer) context.player()).chunk((ServerPlayer) context.player(), payload));
        registrar.playToServer(ImporterPayloads.Cancel.TYPE, ImporterPayloads.Cancel.CODEC,
                (payload, context) -> ImporterUploads.get((ServerPlayer) context.player()).cancel((ServerPlayer) context.player(), payload.token()));
    }
}
