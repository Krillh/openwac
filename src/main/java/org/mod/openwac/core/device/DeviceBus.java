package org.mod.openwac.core.device;

import java.util.*;

/** Machine-local registry; addresses are permanent UUIDs, enumeration indices are transient. */
public final class DeviceBus {
    private final NavigableMap<UUID, Device> devices = new TreeMap<>();
    public synchronized void attach(Device device) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(device.id());
        Objects.requireNonNull(device.interfaceId());
        if (devices.size() >= 64) throw new IllegalStateException("Device limit reached");
        if (devices.putIfAbsent(device.id(), device) != null) throw new IllegalArgumentException("Device UUID already attached: " + device.id());
    }
    public synchronized Optional<Device> detach(UUID id) { return Optional.ofNullable(devices.remove(id)); }
    public synchronized int size() { return devices.size(); }
    public synchronized List<Device> devices() { return List.copyOf(devices.values()); }
    public synchronized List<DeviceDescriptor> enumerate() {
        return devices.values().stream().map(d -> new DeviceDescriptor(d.id(), d.interfaceId())).toList();
    }
    public DeviceResult device_call(UUID id, DeviceCall call) {
        Device device;
        synchronized (this) { device = devices.get(id); }
        if (device == null) return DeviceResult.error(-7);
        try {
            DeviceResult result = device.device_call(call);
            if (result.response().length > call.responseLimit()) return DeviceResult.error(-5);
            return result;
        } catch (RuntimeException failure) { return DeviceResult.error(-6); }
    }
    public record DeviceDescriptor(UUID id, InterfaceId interfaceId) {}
}
