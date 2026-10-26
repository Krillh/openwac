package org.mod.openwac.core.device.storage;

import org.mod.openwac.core.device.*;
import static org.mod.openwac.core.device.DeviceStatus.*;
import java.io.UncheckedIOException;
import java.nio.*;
import java.util.*;

/** Persistent disk wire contract, interface 0x0100. All values are response bytes, never statuses. */
public final class DiskDevice implements Device {
    public static final InterfaceId INTERFACE = new InterfaceId(0x0100, "openwac:disk", 1, 0);
    public static final int BLOCK_SIZE = 0x10,
            BLOCK_COUNT = 0x11, READ_BLOCK = 0x20, WRITE_BLOCK = 0x30;
    private final BlockStorage media;
    public DiskDevice(BlockStorage media) { this.media = Objects.requireNonNull(media); }
    @Override public UUID id() { return media.id(); }
    @Override public InterfaceId interfaceId() { return INTERFACE; }
    private DeviceResult response(DeviceCall call, byte[] bytes) {
        return call.responseLimit() < bytes.length ? DeviceResult.error(RESPONSE_OVERRUN) : DeviceResult.ok(bytes);
    }
    private DeviceResult number(DeviceCall call, int value) {
        return response(call, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }
    @Override public DeviceResult device_call(DeviceCall call) {
        byte[] args = call.arguments();
        try {
            return switch (call.operation()) {
                case BLOCK_SIZE, BLOCK_COUNT -> args.length != 0 ? DeviceResult.error(INVALID_ARGUMENT)
                        : number(call, call.operation() == BLOCK_SIZE ? media.blockSize() : Math.toIntExact(media.blockCount()));
                case READ_BLOCK, WRITE_BLOCK -> transfer(call, args);
                default -> DeviceResult.error(INVALID_OPERATION);
            };
        } catch (UncheckedIOException failure) { return DeviceResult.error(DEVICE_BROKEN); }
    }
    private DeviceResult transfer(DeviceCall call, byte[] args) {
        if (args.length < 4) return DeviceResult.error(ARGUMENT_OVERRUN);
        long block = Integer.toUnsignedLong(ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN).getInt());
        if (block >= media.blockCount()) return DeviceResult.error(OUT_OF_BOUNDS);
        int size = media.blockSize();
        if (call.operation() == READ_BLOCK) {
            if (args.length != 4) return DeviceResult.error(INVALID_ARGUMENT);
            if (call.responseLimit() < size) return DeviceResult.error(RESPONSE_OVERRUN);
            byte[] bytes = new byte[size];
            return media.read(block, 1, bytes, 0) == size ? DeviceResult.ok(bytes) : DeviceResult.error(DEVICE_BROKEN);
        }
        if (args.length < 4L + size) return DeviceResult.error(ARGUMENT_OVERRUN);
        if (args.length != 4L + size) return DeviceResult.error(INVALID_ARGUMENT);
        if (media.write(block, 1, args, 4) != size) return DeviceResult.error(DEVICE_BROKEN);
        media.flush();
        return DeviceResult.ok(new byte[0]);
    }
    @Override public DeviceSnapshot snapshot() {
        return new DeviceSnapshot(1, id(), "openwac:disk", Map.of(
                "blockSize", Integer.toString(media.blockSize()), "blockCount", Long.toString(media.blockCount())));
    }
}
