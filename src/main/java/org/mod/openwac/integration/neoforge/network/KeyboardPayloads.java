package org.mod.openwac.integration.neoforge.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

public final class KeyboardPayloads {
    private KeyboardPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("openwac", name));
    }
    public record Capture(UUID token, BlockPos pos, UUID device, BlockPos displayPos, UUID displayDevice) implements CustomPacketPayload {
        public static final Type<Capture> TYPE = KeyboardPayloads.type("keyboard_capture");
        public static final StreamCodec<RegistryFriendlyByteBuf, Capture> CODEC = CustomPacketPayload.codec(
                (p,b) -> {
                    b.writeUUID(p.token); b.writeBlockPos(p.pos); b.writeUUID(p.device);
                    b.writeBoolean(p.displayPos != null);
                    if (p.displayPos != null) { b.writeBlockPos(p.displayPos); b.writeUUID(p.displayDevice); }
                },
                b -> {
                    UUID token = b.readUUID(); BlockPos pos = b.readBlockPos(); UUID device = b.readUUID();
                    return b.readBoolean() ? new Capture(token, pos, device, b.readBlockPos(), b.readUUID())
                            : new Capture(token, pos, device, null, null);
                });
        @Override public Type<Capture> type() { return TYPE; }
    }
    public record Input(UUID token, int kind, int scancode, int keycode, int modifiers, int codepoint) implements CustomPacketPayload {
        public static final Type<Input> TYPE = KeyboardPayloads.type("keyboard_input");
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeUUID(p.token); b.writeInt(p.kind); b.writeInt(p.scancode); b.writeInt(p.keycode); b.writeInt(p.modifiers); b.writeInt(p.codepoint); },
                b -> new Input(b.readUUID(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt()));
        @Override public Type<Input> type() { return TYPE; }
    }
    public record Stop(UUID token) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = KeyboardPayloads.type("keyboard_stop");
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = CustomPacketPayload.codec((p,b) -> b.writeUUID(p.token), b -> new Stop(b.readUUID()));
        @Override public Type<Stop> type() { return TYPE; }
    }
    public record Heartbeat(UUID token) implements CustomPacketPayload {
        public static final Type<Heartbeat> TYPE = KeyboardPayloads.type("keyboard_heartbeat");
        public static final StreamCodec<RegistryFriendlyByteBuf, Heartbeat> CODEC = CustomPacketPayload.codec((p,b) -> b.writeUUID(p.token), b -> new Heartbeat(b.readUUID()));
        @Override public Type<Heartbeat> type() { return TYPE; }
    }
    public record Closed(UUID token, String reason) implements CustomPacketPayload {
        public static final Type<Closed> TYPE = KeyboardPayloads.type("keyboard_closed");
        public static final StreamCodec<RegistryFriendlyByteBuf, Closed> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeUUID(p.token); b.writeUtf(p.reason, 256); }, b -> new Closed(b.readUUID(), b.readUtf(256)));
        @Override public Type<Closed> type() { return TYPE; }
    }
}
