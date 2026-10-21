package org.mod.openwac.integration.neoforge.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.mod.openwac.core.device.input.KeyboardDevice;
import org.mod.openwac.integration.neoforge.blockentity.KeyboardBlockEntity;
import java.util.*;

/** Main-thread session authorization. Clients cannot select a target through input packets. */
public final class KeyboardNetwork {
    private static final Map<MinecraftServer, KeyboardNetwork> SERVERS = new IdentityHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private static final class Session {
        final UUID token = UUID.randomUUID();
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos pos;
        final KeyboardDevice device;
        final long generation;
        long seen = System.nanoTime(), budgetTime = seen;
        double credits = 256;
        Session(ServerPlayer player, BlockPos pos, KeyboardDevice device) {
            this.player = player; this.level = player.level(); this.pos = pos.immutable();
            this.device = device; generation = device.generation();
        }
    }
    public static KeyboardNetwork get(ServerPlayer player) {
        return SERVERS.computeIfAbsent(Objects.requireNonNull(player.level().getServer()), server -> new KeyboardNetwork());
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToClient(KeyboardPayloads.Capture.TYPE, KeyboardPayloads.Capture.CODEC);
        registrar.playToClient(KeyboardPayloads.Closed.TYPE, KeyboardPayloads.Closed.CODEC);
        registrar.playToServer(KeyboardPayloads.Input.TYPE, KeyboardPayloads.Input.CODEC,
                (packet, context) -> get((ServerPlayer)context.player()).input((ServerPlayer)context.player(), packet));
        registrar.playToServer(KeyboardPayloads.Stop.TYPE, KeyboardPayloads.Stop.CODEC, (packet, context) -> {
            var manager = get((ServerPlayer)context.player());
            Session session = manager.session((ServerPlayer)context.player(), packet.token());
            if (session != null) manager.end(session, "");
        });
        registrar.playToServer(KeyboardPayloads.Heartbeat.TYPE, KeyboardPayloads.Heartbeat.CODEC, (packet, context) -> {
            var manager = get((ServerPlayer)context.player());
            Session session = manager.session((ServerPlayer)context.player(), packet.token());
            if (session != null) {
                if (manager.valid(session)) session.seen = System.nanoTime();
                else manager.end(session, "Keyboard connection ended.");
            }
        });
    }
    public void toggle(ServerPlayer player, BlockPos pos) {
        capture(player, pos, null, null, true);
    }
    public boolean engageDisplay(ServerPlayer player, BlockPos displayPos, UUID displayDevice) {
        for (Direction direction : Direction.values()) {
            BlockPos pos = displayPos.relative(direction);
            if (capture(player, pos, displayPos, displayDevice, false)) return true;
        }
        return false;
    }
    private boolean capture(ServerPlayer player, BlockPos pos, BlockPos displayPos, UUID displayDevice, boolean toggle) {
        if (player.isSpectator() || !player.isAlive() || player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 64
                || !player.level().hasChunkAt(pos) || !player.level().mayInteract(player, pos)) return false;
        if (!(player.level().getBlockEntity(pos) instanceof KeyboardBlockEntity keyboard)) return false;
        Session old = sessions.get(player.getUUID());
        if (old != null && valid(old) && old.level == player.level() && old.pos.equals(pos)) {
            if (toggle) { end(old, ""); return false; }
            PacketDistributor.sendToPlayer(player, new KeyboardPayloads.Capture(old.token, pos, keyboard.peripheralId(), displayPos, displayDevice));
            return true;
        }
        if (!keyboard.device().isAttached()) {
            if (toggle) player.sendSystemMessage(Component.translatable("message.openwac.keyboard.disconnected"));
            return false;
        }
        for (Session session : List.copyOf(sessions.values())) if (!valid(session)) end(session, "Keyboard connection ended.");
        old = sessions.get(player.getUUID());
        if (old == null && sessions.size() >= 64) {
            if (toggle) player.sendSystemMessage(Component.translatable("message.openwac.keyboard.busy"));
            return false;
        }
        Session session = new Session(player, pos, keyboard.device());
        if (!session.device.beginCapture(session.generation)) {
            if (toggle) player.sendSystemMessage(Component.translatable("message.openwac.keyboard.busy"));
            return false;
        }
        if (old != null) end(old, "");
        sessions.put(player.getUUID(), session);
        PacketDistributor.sendToPlayer(player, new KeyboardPayloads.Capture(session.token, pos, keyboard.peripheralId(), displayPos, displayDevice));
        return true;
    }
    private Session session(ServerPlayer player, UUID token) {
        Session session = sessions.get(player.getUUID());
        return session != null && session.token.equals(token) ? session : null;
    }
    private boolean valid(Session session) {
        ServerPlayer player = session.player;
        return !player.hasDisconnected() && player.isAlive() && !player.isSpectator() && player.level() == session.level
                && System.nanoTime() - session.seen <= 5_000_000_000L && player.position().distanceToSqr(Vec3.atCenterOf(session.pos)) <= 64
                && session.level.hasChunkAt(session.pos) && session.level.mayInteract(player, session.pos)
                && session.level.getBlockEntity(session.pos) instanceof KeyboardBlockEntity keyboard
                && keyboard.device() == session.device && session.device.isAttached() && session.device.generation() == session.generation;
    }
    private void input(ServerPlayer player, KeyboardPayloads.Input packet) {
        Session session = session(player, packet.token()); if (session == null) return;
        if (!valid(session)) { end(session, "Keyboard connection ended."); return; }
        long now = System.nanoTime();
        session.credits = Math.min(256, session.credits + (now - session.budgetTime) * (128.0 / 1_000_000_000));
        session.budgetTime = now;
        if (session.credits < 1) { end(session, "Keyboard input rate limit exceeded."); return; }
        session.credits--;
        boolean text = packet.kind() == KeyboardDevice.TEXT;
        boolean valid = packet.modifiers() >= 0 && packet.modifiers() <= 63 && (text
                ? packet.scancode() == 0 && packet.keycode() == 0 && packet.codepoint() > 0
                    && Character.isValidCodePoint(packet.codepoint()) && !(packet.codepoint() >= 0xd800 && packet.codepoint() <= 0xdfff)
                : packet.kind() >= KeyboardDevice.KEY_DOWN && packet.kind() <= KeyboardDevice.KEY_REPEAT
                    && packet.scancode() >= 0 && packet.scancode() < 512 && packet.keycode() >= 0 && packet.codepoint() == 0);
        if (!valid) { end(session, "Invalid keyboard event."); return; }
        session.seen = now;
        if (!session.device.input(session.generation, packet.kind(), packet.scancode(), packet.keycode(), packet.modifiers(), packet.codepoint()))
            end(session, "Keyboard connection ended.");
    }
    private void end(Session session, String reason) {
        sessions.remove(session.player.getUUID(), session);
        session.device.endCapture(session.generation);
        if (!session.player.hasDisconnected()) PacketDistributor.sendToPlayer(session.player, new KeyboardPayloads.Closed(session.token, reason));
    }
    public static void tick(MinecraftServer server) {
        KeyboardNetwork manager = SERVERS.get(server);
        if (manager != null) for (Session session : List.copyOf(manager.sessions.values()))
            if (!manager.valid(session)) manager.end(session, "Keyboard connection ended.");
    }
    public static void stop(MinecraftServer server) {
        KeyboardNetwork manager = SERVERS.remove(server);
        if (manager != null) for (Session session : List.copyOf(manager.sessions.values())) manager.end(session, "Server stopping.");
    }
}
