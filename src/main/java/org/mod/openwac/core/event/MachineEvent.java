package org.mod.openwac.core.event;

import java.util.Arrays;
import java.util.Objects;

public record MachineEvent(String type, long tick, byte[] payload) {
    public MachineEvent {
        Objects.requireNonNull(type);
        if (type.length() > 128 || payload == null || payload.length > 64 * 1024) throw new IllegalArgumentException("Event exceeds limits");
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
