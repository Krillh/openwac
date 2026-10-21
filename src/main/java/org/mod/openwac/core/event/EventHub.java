package org.mod.openwac.core.event;

import java.util.ArrayDeque;
import java.util.Optional;

public final class EventHub {
    private final ArrayDeque<MachineEvent> events = new ArrayDeque<>();
    private final int capacity;
    public EventHub(int capacity) { if (capacity < 1) throw new IllegalArgumentException(); this.capacity = capacity; }
    public synchronized boolean offer(MachineEvent event) {
        if (events.size() >= capacity) return false;
        events.addLast(event); return true;
    }
    public synchronized Optional<MachineEvent> poll() { return Optional.ofNullable(events.pollFirst()); }
    /** Oversize head messages remain queued; the caller can retry with a larger buffer. */
    public synchronized Optional<byte[]> pollPayload(int capacity) {
        MachineEvent next = events.peekFirst();
        if (next == null) return Optional.empty();
        byte[] payload = next.payload();
        if (payload.length <= capacity) events.removeFirst();
        return Optional.of(payload);
    }
    public synchronized int size() { return events.size(); }
}
