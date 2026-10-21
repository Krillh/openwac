package org.mod.openwac.core.device.display;

import java.util.*;

/** Revisioned fixed-cell display with row-level dirty tracking. */
public final class TerminalBuffer {
    public record Cell(int codePoint, int foreground, int background, int attributes) {}
    public record RowDelta(int row, List<Cell> cells) {}
    public record Delta(long revision, List<RowDelta> rows) {}
    private final int width, height;
    private final Cell[] cells;
    private final BitSet dirtyRows;
    private long revision;
    private int cursorX, cursorY;
    public TerminalBuffer(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > 65_536) throw new IllegalArgumentException("Invalid display dimensions");
        this.width = width; this.height = height; this.cells = new Cell[width * height];
        Arrays.fill(cells, new Cell(' ', 0xFFFFFF, 0x000000, 0));
        dirtyRows = new BitSet(height); dirtyRows.set(0, height);
    }
    public synchronized void setCell(int x, int y, Cell cell) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException();
        cells[y * width + x] = Objects.requireNonNull(cell); dirtyRows.set(y); revision++;
    }
    public synchronized void setCursor(int x, int y) { cursorX = Math.max(0, Math.min(width - 1, x)); cursorY = Math.max(0, Math.min(height - 1, y)); revision++; }
    public synchronized int cursorX() { return cursorX; }
    public synchronized int cursorY() { return cursorY; }
    public int width() { return width; }
    public int height() { return height; }
    public synchronized Delta drainDirtyRows() {
        List<RowDelta> rows = new ArrayList<>();
        for (int y = dirtyRows.nextSetBit(0); y >= 0; y = dirtyRows.nextSetBit(y + 1))
            rows.add(new RowDelta(y, List.copyOf(Arrays.asList(Arrays.copyOfRange(cells, y * width, (y + 1) * width)))));
        dirtyRows.clear(); return new Delta(revision, List.copyOf(rows));
    }
}
