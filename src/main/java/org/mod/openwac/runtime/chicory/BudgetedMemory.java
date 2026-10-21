package org.mod.openwac.runtime.chicory;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.DataSegment;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import org.mod.openwac.core.machine.MemoryBudget;

/** Delegates linear-memory operations while accounting for allocated pages at both scopes. */
@SuppressWarnings("removal")
final class BudgetedMemory implements Memory, AutoCloseable {
    private static final MemoryBudget SERVER_MEMORY = new MemoryBudget(256L * 1024 * 1024);
    private final ByteArrayMemory memory;
    private final MemoryBudget computer, server;
    private int reservedPages;
    private boolean closed;

    BudgetedMemory(MemoryLimits limits, MemoryBudget computer) {
        this(limits, computer, SERVER_MEMORY);
    }

    BudgetedMemory(MemoryLimits limits, MemoryBudget computer, MemoryBudget server) {
        this.computer = computer;
        this.server = server;
        if (limits.shared() || limits.initialPages() > limits.maximumPages())
            throw new IllegalArgumentException("Invalid memory limits");
        int initial = limits.initialPages();
        if (!reserve(initial)) throw new IllegalStateException("Not enough RAM for initial WASM memory: requested " + initial
                + " pages; computer available " + (computer.capacityPages() - computer.usedPages())
                + " of " + computer.capacityPages() + " pages; server available " + (server.capacityPages() - server.usedPages()) + " pages");
        boolean allocated = false;
        try {
            memory = new ByteArrayMemory(limits);
            reservedPages = initial;
            allocated = true;
        } finally {
            if (!allocated) release(initial);
        }
    }

    private boolean reserve(int pages) {
        if (!computer.tryReserve(pages)) return false;
        if (server.tryReserve(pages)) return true;
        computer.release(pages);
        return false;
    }

    private void release(int pages) {
        server.release(pages);
        computer.release(pages);
    }

    @Override public synchronized int grow(int pages) {
        if (closed || pages < 0 || pages > maximumPages() - memory.pages()) return -1;
        if (!reserve(pages)) return -1;
        boolean grown = false;
        try {
            int previous = memory.grow(pages);
            if (previous < 0) return -1;
            reservedPages += pages;
            grown = true;
            return previous;
        } finally {
            if (!grown) release(pages);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        release(reservedPages);
        reservedPages = 0;
    }

    @Override public int pages() { return memory.pages(); }
    @Override public int initialPages() { return memory.initialPages(); }
    @Override public int maximumPages() { return memory.maximumPages(); }
    @Override public boolean shared() { return memory.shared(); }
    @Override public Object lock(int address) { return memory.lock(address); }
    @Override public int waitOn(int address, int expected, long timeout) { return memory.waitOn(address, expected, timeout); }
    @Override public int waitOn(int address, long expected, long timeout) { return memory.waitOn(address, expected, timeout); }
    @Override public int notify(int address, int maxThreads) { return memory.notify(address, maxThreads); }
    @Override public void initialize(Instance instance, DataSegment[] dataSegments) { memory.initialize(instance, dataSegments); }
    @Override public void initPassiveSegment(int segmentId, int dest, int offset, int size) { memory.initPassiveSegment(segmentId, dest, offset, size); }
    @Override public void write(int addr, byte[] data, int offset, int size) { memory.write(addr, data, offset, size); }
    @Override public byte read(int addr) { return memory.read(addr); }
    @Override public byte[] readBytes(int addr, int len) { return memory.readBytes(addr, len); }
    @Override public void writeI32(int addr, int data) { memory.writeI32(addr, data); }
    @Override public int readInt(int addr) { return memory.readInt(addr); }
    @Override public void writeLong(int addr, long data) { memory.writeLong(addr, data); }
    @Override public long readLong(int addr) { return memory.readLong(addr); }
    @Override public void writeShort(int addr, short data) { memory.writeShort(addr, data); }
    @Override public short readShort(int addr) { return memory.readShort(addr); }
    @Override public long readU16(int addr) { return memory.readU16(addr); }
    @Override public void writeByte(int addr, byte data) { memory.writeByte(addr, data); }
    @Override public void writeF32(int addr, float data) { memory.writeF32(addr, data); }
    @Override public long readF32(int addr) { return memory.readF32(addr); }
    @Override public float readFloat(int addr) { return memory.readFloat(addr); }
    @Override public void writeF64(int addr, double data) { memory.writeF64(addr, data); }
    @Override public double readDouble(int addr) { return memory.readDouble(addr); }
    @Override public long readF64(int addr) { return memory.readF64(addr); }
    @Override public void zero() { memory.zero(); }
    @Override public void fill(byte value, int fromIndex, int toIndex) { memory.fill(value, fromIndex, toIndex); }
    @Override public void drop(int segment) { memory.drop(segment); }
}
