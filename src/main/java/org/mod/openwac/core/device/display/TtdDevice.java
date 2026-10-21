package org.mod.openwac.core.device.display;

import org.mod.openwac.core.device.*;
import static org.mod.openwac.core.device.DeviceStatus.*;
import java.nio.*;
import java.util.*;

/** TTD hardware and exclusive attachment leases; stale computer instances cannot continue drawing. */
public final class TtdDevice {
    public static final InterfaceId INTERFACE = new InterfaceId(0x0400, "openwac:ttd", 1, 0);
    public static final int GEOMETRY = 0, READ_CELLS = 0x10, WRITE_CELLS = 0x20,
            FILL = 0x21, COPY = 0x22, SET_CURSOR = 0x30, GET_CURSOR = 0x31;
    private final UUID id;
    private final TtdBuffer buffer;
    private UUID owner;
    private long generation;
    private boolean available = true, powered;
    public TtdDevice(UUID id, TtdBuffer buffer) { this.id = Objects.requireNonNull(id); this.buffer = Objects.requireNonNull(buffer); }
    public UUID id() { return id; }
    public synchronized Optional<Device> claim(UUID computer) {
        if (!available || owner != null) return Optional.empty();
        owner = Objects.requireNonNull(computer);
        powered = true;
        long lease = ++generation;
        return Optional.of(new Device() {
            @Override public UUID id() { return id; }
            @Override public InterfaceId interfaceId() { return INTERFACE; }
            @Override public DeviceResult device_call(DeviceCall call) {
                synchronized (TtdDevice.this) {
                    return available && powered && generation == lease && computer.equals(owner) ? invoke(call) : DeviceResult.error(DEVICE_NOT_AVAILABLE);
                }
            }
            @Override public DeviceSnapshot snapshot() { return new DeviceSnapshot(1, id, "openwac:ttd", Map.of()); }
        });
    }
    public synchronized void release(UUID computer) {
        if (computer.equals(owner)) { owner = null; powered = false; generation++; }
    }
    public synchronized boolean ownedBy(UUID computer) { return available && computer.equals(owner); }
    public synchronized boolean isPowered() { return available && owner != null && powered; }
    /** Host power policy may disable supply while retaining the connection and character memory. */
    public synchronized void setPowered(boolean enabled) { powered = enabled && available && owner != null; }
    public synchronized void invalidate() { available = false; powered = false; owner = null; generation++; }
    private DeviceResult invoke(DeviceCall call) {
        byte[] args = call.arguments();
        int op = call.operation();
        if (op == GEOMETRY || op == GET_CURSOR) {
            if (args.length != 0) return DeviceResult.error(INVALID_ARGUMENT);
            int count = op == GEOMETRY ? 8 : 10;
            if (call.responseLimit() < count) return DeviceResult.error(RESPONSE_OVERRUN);
            ByteBuffer out = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
            if (op == GEOMETRY) out.putInt(TtdBuffer.WIDTH).putInt(TtdBuffer.HEIGHT);
            else {
                var cursor = buffer.snapshot().cursor();
                out.putInt(cursor.x()).putInt(cursor.y()).put((byte)(cursor.visible() ? 1 : 0)).put((byte)(cursor.blink() ? 1 : 0));
            }
            return DeviceResult.ok(out.array());
        }
        if (op == SET_CURSOR) {
            if (args.length < 10) return DeviceResult.error(ARGUMENT_OVERRUN);
            if (args.length != 10 || args[8] < 0 || args[8] > 1 || args[9] < 0 || args[9] > 1) return DeviceResult.error(INVALID_ARGUMENT);
            ByteBuffer input = ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN);
            int x = input.getInt(), y = input.getInt();
            if (!TtdBuffer.fits(x, y, 1, 1)) return DeviceResult.error(OUT_OF_BOUNDS);
            buffer.cursor(new TtdBuffer.Cursor(x, y, args[8] != 0, args[9] != 0));
            return DeviceResult.ok(new byte[0]);
        }
        if (op != READ_CELLS && op != WRITE_CELLS && op != FILL && op != COPY) return DeviceResult.error(INVALID_OPERATION);
        if (args.length < 16) return DeviceResult.error(ARGUMENT_OVERRUN);
        ByteBuffer input = ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN);
        int x = input.getInt(), y = input.getInt(), width = input.getInt(), height = input.getInt();
        if (!TtdBuffer.fits(x, y, width, height)) return DeviceResult.error(OUT_OF_BOUNDS);
        int expected = switch (op) { case WRITE_CELLS -> 16 + width * height; case FILL -> 17; case COPY -> 24; default -> 16; };
        if (args.length < expected) return DeviceResult.error(ARGUMENT_OVERRUN);
        if (args.length != expected) return DeviceResult.error(INVALID_ARGUMENT);
        if (op == READ_CELLS) {
            if (call.responseLimit() < width * height) return DeviceResult.error(RESPONSE_OVERRUN);
            return DeviceResult.ok(buffer.read(x, y, width, height));
        }
        if (op == WRITE_CELLS) buffer.write(x, y, width, height, Arrays.copyOfRange(args, 16, args.length));
        else if (op == FILL) buffer.fill(x, y, width, height, args[16]);
        else {
            int targetX = input.getInt(), targetY = input.getInt();
            if (!TtdBuffer.fits(targetX, targetY, width, height)) return DeviceResult.error(OUT_OF_BOUNDS);
            buffer.copy(x, y, width, height, targetX, targetY);
        }
        return DeviceResult.ok(new byte[0]);
    }
}
