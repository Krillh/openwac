package org.mod.openwac.core.device;

import java.nio.*;
import java.util.*;

/** Bus switch: attached peripherals retain their own HAL addresses and interfaces. */
public final class PeripheralPortDevice implements Device {
    public static final InterfaceId INTERFACE = new InterfaceId(0x0500, "openwac:peripheral_port", 1, 0);
    public static final int INFO = 0;
    private final UUID id;
    public PeripheralPortDevice(UUID id) { this.id = Objects.requireNonNull(id); }
    @Override public UUID id() { return id; }
    @Override public InterfaceId interfaceId() { return INTERFACE; }
    @Override public DeviceResult device_call(DeviceCall call) {
        if (call.operation() != INFO) return DeviceResult.error(DeviceStatus.INVALID_OPERATION);
        if (call.arguments().length != 0) return DeviceResult.error(DeviceStatus.INVALID_ARGUMENT);
        if (call.responseLimit() < 4) return DeviceResult.error(DeviceStatus.RESPONSE_OVERRUN);
        // Number of directly adjacent external connection positions, not current attached-device count.
        return DeviceResult.ok(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(6).array());
    }
    @Override public DeviceSnapshot snapshot() { return new DeviceSnapshot(1, id, "openwac:peripheral_port", Map.of()); }
}
