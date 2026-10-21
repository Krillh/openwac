package org.mod.openwac.integration.neoforge.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.mod.openwac.core.device.display.TtdBuffer;
import java.util.*;

public final class TtdPayloads {
    private TtdPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("openwac", name));
    }
    public record Open(BlockPos pos, UUID device) implements CustomPacketPayload {
        public static final Type<Open> TYPE = TtdPayloads.type("ttd_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeBlockPos(p.pos); b.writeUUID(p.device); }, b -> new Open(b.readBlockPos(), b.readUUID()));
        @Override public Type<Open> type() { return TYPE; }
    }
    public record Request(BlockPos pos, UUID device) implements CustomPacketPayload {
        public static final Type<Request> TYPE = TtdPayloads.type("ttd_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeBlockPos(p.pos); b.writeUUID(p.device); }, b -> new Request(b.readBlockPos(), b.readUUID()));
        @Override public Type<Request> type() { return TYPE; }
    }
    /** Power changes are independent of cell revisions, including when the buffer is unchanged. */
    public record Power(BlockPos pos, UUID device, boolean powered) implements CustomPacketPayload {
        public static final Type<Power> TYPE = TtdPayloads.type("ttd_power");
        public static final StreamCodec<RegistryFriendlyByteBuf, Power> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeBlockPos(p.pos); b.writeUUID(p.device); b.writeBoolean(p.powered); },
                b -> new Power(b.readBlockPos(), b.readUUID(), b.readBoolean()));
        @Override public Type<Power> type() { return TYPE; }
    }
    /** base == -1 denotes a full snapshot; otherwise the client must have exactly base. */
    public record Update(BlockPos pos, UUID device, long base, long revision, List<TtdBuffer.Span> spans, TtdBuffer.Cursor cursor) implements CustomPacketPayload {
        public Update { spans = List.copyOf(spans); }
        public static final Type<Update> TYPE = TtdPayloads.type("ttd_update");
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = CustomPacketPayload.codec(Update::write, Update::read);
        public static Update delta(BlockPos pos, UUID id, TtdBuffer.Delta delta) {
            return new Update(pos, id, delta.base(), delta.revision(), delta.spans(), delta.cursor());
        }
        public static Update full(BlockPos pos, UUID id, TtdBuffer.Snapshot snapshot) {
            List<TtdBuffer.Span> rows = new ArrayList<>(); byte[] cells = snapshot.cells();
            for (int y = 0; y < TtdBuffer.HEIGHT; y++) rows.add(new TtdBuffer.Span(0, y, Arrays.copyOfRange(cells, y * TtdBuffer.WIDTH, (y + 1) * TtdBuffer.WIDTH)));
            return new Update(pos, id, -1, snapshot.revision(), rows, snapshot.cursor());
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeBlockPos(pos); b.writeUUID(device); b.writeLong(base); b.writeLong(revision);
            b.writeVarInt(spans.size());
            for (var span : spans) { b.writeVarInt(span.x()); b.writeVarInt(span.y()); b.writeByteArray(span.cells()); }
            b.writeVarInt(cursor.x()); b.writeVarInt(cursor.y()); b.writeBoolean(cursor.visible()); b.writeBoolean(cursor.blink());
        }
        private static Update read(RegistryFriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos(); UUID id = b.readUUID(); long base = b.readLong(), revision = b.readLong();
            int count = b.readVarInt();
            if (base < -1 || revision < 0 || count < 0 || count > TtdBuffer.HEIGHT) throw new IllegalArgumentException("Invalid TTD update");
            List<TtdBuffer.Span> spans = new ArrayList<>(count);
            int previousRow = -1;
            for (int i = 0; i < count; i++) {
                int x = b.readVarInt(), y = b.readVarInt(); byte[] bytes = b.readByteArray(TtdBuffer.WIDTH);
                if (!TtdBuffer.fits(x, y, bytes.length, 1) || y <= previousRow) throw new IllegalArgumentException("Invalid TTD span");
                spans.add(new TtdBuffer.Span(x, y, bytes)); previousRow = y;
            }
            var cursor = new TtdBuffer.Cursor(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean());
            if (!TtdBuffer.fits(cursor.x(), cursor.y(), 1, 1)) throw new IllegalArgumentException("Invalid TTD cursor");
            if (base == -1 && (spans.size() != TtdBuffer.HEIGHT || spans.stream().anyMatch(s -> s.x() != 0 || s.cells().length != TtdBuffer.WIDTH)))
                throw new IllegalArgumentException("Incomplete TTD snapshot");
            return new Update(pos, id, base, revision, spans, cursor);
        }
        @Override public Type<Update> type() { return TYPE; }
    }
}
