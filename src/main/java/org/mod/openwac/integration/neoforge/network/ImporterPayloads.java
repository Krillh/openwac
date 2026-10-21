package org.mod.openwac.integration.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** No payload contains the client's absolute path. Packet decoders enforce allocation bounds. */
public final class ImporterPayloads {
    public static final int CHUNK_BYTES = 16 * 1024;
    private ImporterPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("openwac", name));
    }
    public record Open(UUID token, UUID device) implements CustomPacketPayload {
        public static final Type<Open> TYPE = ImporterPayloads.type("importer_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.token); b.writeUUID(p.device); }, b -> new Open(b.readUUID(), b.readUUID()));
        @Override public Type<Open> type() { return TYPE; }
    }
    public record Begin(UUID token, String filename, long size) implements CustomPacketPayload {
        public static final Type<Begin> TYPE = ImporterPayloads.type("importer_begin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Begin> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.token); b.writeUtf(p.filename, 255); b.writeLong(p.size); },
                b -> new Begin(b.readUUID(), b.readUtf(255), b.readLong()));
        @Override public Type<Begin> type() { return TYPE; }
    }
    public record Chunk(UUID token, long offset, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = ImporterPayloads.type("importer_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.token); b.writeLong(p.offset); b.writeByteArray(p.data); },
                b -> new Chunk(b.readUUID(), b.readLong(), b.readByteArray(CHUNK_BYTES)));
        @Override public Type<Chunk> type() { return TYPE; }
    }
    public record Cancel(UUID token) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = ImporterPayloads.type("importer_cancel");
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUUID(p.token), b -> new Cancel(b.readUUID()));
        @Override public Type<Cancel> type() { return TYPE; }
    }
    public record Reply(UUID token, long offset, boolean finished, String error) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = ImporterPayloads.type("importer_reply");
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.token); b.writeLong(p.offset); b.writeBoolean(p.finished); b.writeUtf(p.error, 256); },
                b -> new Reply(b.readUUID(), b.readLong(), b.readBoolean(), b.readUtf(256)));
        @Override public Type<Reply> type() { return TYPE; }
    }
}
