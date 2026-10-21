package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.mod.openwac.integration.neoforge.blockentity.KeyboardBlockEntity;
import org.mod.openwac.integration.neoforge.network.KeyboardPayloads;

public final class KeyboardClientNetwork {
    private static KeyboardCaptureScreen active;
    private KeyboardClientNetwork() {}
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(KeyboardPayloads.Capture.TYPE, (packet, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null && client.player != null && client.player.position().distanceToSqr(Vec3.atCenterOf(packet.pos())) <= 64
                    && client.level.getBlockEntity(packet.pos()) instanceof KeyboardBlockEntity keyboard && keyboard.peripheralId().equals(packet.device())) {
                if (active == null || !active.matches(packet.token())) {
                    if (active != null) active.onClose();
                    active = new KeyboardCaptureScreen(packet.token(), packet.pos(), packet.device());
                }
                active.showDisplay(packet.displayPos(), packet.displayDevice());
                active.resume();
            } else ClientPacketDistributor.sendToServer(new KeyboardPayloads.Stop(packet.token()));
        });
        event.register(KeyboardPayloads.Closed.TYPE, (packet, context) -> {
            if (active != null) active.serverClosed(packet);
        });
    }
    public static void tick(ClientTickEvent.Post event) {
        if (active == null) return;
        active.tickSession();
        if (active.isClosed()) active = null;
    }
    public static void key(InputEvent.Key event) {
        if (active != null) active.worldKey(event.getKey(), event.getAction());
    }
}
