package org.mod.openwac.core.machine;

/** Thread-safe ledger of allocated WASM pages, independent of the runtime implementation. */
public final class MemoryBudget {
    public static final int PAGE_BYTES = 65_536;
    private final int capacityPages;
    private int usedPages;

    public MemoryBudget(long capacityBytes) {
        if (capacityBytes < PAGE_BYTES || capacityBytes > 256L * 1024 * 1024 || capacityBytes % PAGE_BYTES != 0)
            throw new IllegalArgumentException("Invalid memory capacity");
        capacityPages = Math.toIntExact(capacityBytes / PAGE_BYTES);
    }

    public synchronized boolean tryReserve(int pages) {
        if (pages < 0 || pages > capacityPages - usedPages) return false;
        usedPages += pages;
        return true;
    }

    public synchronized void release(int pages) {
        if (pages < 0 || pages > usedPages) throw new IllegalArgumentException("Invalid memory release");
        usedPages -= pages;
    }

    public int capacityPages() { return capacityPages; }
    public synchronized int usedPages() { return usedPages; }
}
