package org.mod.openwac.core.device.input;

import org.mod.openwac.core.device.*;
import static org.mod.openwac.core.device.DeviceStatus.*;
import java.nio.*;
import java.util.*;

/** Bounded input hardware with exclusive machine leases and a transient event FIFO. */
public final class KeyboardDevice {
    public static final InterfaceId INTERFACE = new InterfaceId(0x0600, "openwac:keyboard", 1, 0);
    public static final int INFO = 0, AVAILABLE = 1, READ_EVENTS = 0x10;
    public static final int KEY_DOWN = 1, KEY_UP = 2, KEY_REPEAT = 3, TEXT = 4, RESET = 5;
    public static final int RECORD_BYTES = 24, CAPACITY = 256;
    private record Event(int type, int scancode, int keycode, int modifiers, int codepoint, int sequence) {}
    private final UUID id;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private UUID owner;
    private boolean available = true, capturing;
    private long generation;
    private int sequence, dropped;
    public KeyboardDevice(UUID id) { this.id = Objects.requireNonNull(id); }
    public UUID id() { return id; }
    public synchronized long generation() { return generation; }
    public synchronized boolean isAttached() { return available && owner != null; }
    public synchronized boolean ownedBy(UUID computer) { return available && computer.equals(owner); }
    public synchronized Optional<Device> claim(UUID computer) {
        if (!available || owner != null) return Optional.empty();
        owner = Objects.requireNonNull(computer); long lease = ++generation;
        return Optional.of(new Device() {
            @Override public UUID id() { return id; }
            @Override public InterfaceId interfaceId() { return INTERFACE; }
            @Override public DeviceResult device_call(DeviceCall call) {
                synchronized (KeyboardDevice.this) {
                    return available && generation == lease && computer.equals(owner) ? invoke(call) : DeviceResult.error(DEVICE_NOT_AVAILABLE);
                }
            }
            @Override public DeviceSnapshot snapshot() { return new DeviceSnapshot(1, id, "openwac:keyboard", Map.of()); }
        });
    }
    public synchronized void release(UUID computer) {
        if (computer.equals(owner)) { owner = null; generation++; capturing = false; events.clear(); }
    }
    public synchronized void invalidate() { available = false; owner = null; generation++; capturing = false; events.clear(); }
    public synchronized boolean beginCapture(long lease) {
        if (!isAttached() || generation != lease || capturing) return false;
        capturing = true; enqueue(RESET, 0, 0, 0, 0); return true;
    }
    public synchronized void endCapture(long lease) {
        if (generation == lease && capturing) { capturing = false; enqueue(RESET, 0, 0, 0, 0); }
    }
    public synchronized boolean input(long lease, int type, int scancode, int keycode, int modifiers, int codepoint) {
        if (!isAttached() || !capturing || generation != lease) return false;
        enqueue(type, scancode, keycode, modifiers, codepoint); return true;
    }
    private void enqueue(int type, int scancode, int keycode, int modifiers, int codepoint) {
        if (events.size() >= CAPACITY) {
            dropped += events.size(); events.clear();
            events.addLast(new Event(RESET, 0, 0, 0, 0, ++sequence));
        }
        events.addLast(new Event(type, scancode, keycode, modifiers, codepoint, ++sequence));
    }
    private DeviceResult invoke(DeviceCall call) {
        if (call.operation() != INFO && call.operation() != AVAILABLE && call.operation() != READ_EVENTS) return DeviceResult.error(INVALID_OPERATION);
        if (call.arguments().length != 0) return DeviceResult.error(INVALID_ARGUMENT);
        int capacity = call.responseLimit();
        if (call.operation() == INFO) {
            if (capacity < 20) return DeviceResult.error(RESPONSE_OVERRUN);
            return DeviceResult.ok(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(RECORD_BYTES).putInt(CAPACITY).putInt(events.size()).putInt(dropped).putInt(capturing ? 1 : 0).array());
        }
        if (capacity < 4) return DeviceResult.error(RESPONSE_OVERRUN);
        if (call.operation() == AVAILABLE) return DeviceResult.ok(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(events.size()).array());
        if (capacity < 4 + RECORD_BYTES) return DeviceResult.error(RESPONSE_OVERRUN);
        int count = Math.min(events.size(), (capacity - 4) / RECORD_BYTES);
        ByteBuffer output = ByteBuffer.allocate(4 + count * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(count);
        for (int i = 0; i < count; i++) {
            Event event = events.removeFirst();
            output.putInt(event.type()).putInt(event.scancode()).putInt(event.keycode()).putInt(event.modifiers()).putInt(event.codepoint()).putInt(event.sequence());
        }
        return DeviceResult.ok(output.array());
    }
}
