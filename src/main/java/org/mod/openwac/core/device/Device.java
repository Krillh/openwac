package org.mod.openwac.core.device;

import java.util.UUID;

public interface Device {
    UUID id();
    InterfaceId interfaceId();
    DeviceResult device_call(DeviceCall call);
    DeviceSnapshot snapshot();
}
