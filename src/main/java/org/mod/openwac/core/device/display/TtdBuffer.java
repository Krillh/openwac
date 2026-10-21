package org.mod.openwac.core.device.display;

import java.util.*;

/** Thread-safe CP437 cell memory. No Minecraft, Unicode decoding, or terminal interpretation. */
public final class TtdBuffer {
    public static final int WIDTH = 60, HEIGHT = 30, CELLS = WIDTH * HEIGHT;
    public static final int GLYPH_WIDTH = 9, GLYPH_HEIGHT = 16;
    public static final int PIXEL_WIDTH = WIDTH * GLYPH_WIDTH, PIXEL_HEIGHT = HEIGHT * GLYPH_HEIGHT;
    public record Cursor(int x, int y, boolean visible, boolean blink) {}
    public record Snapshot(long revision, byte[] cells, Cursor cursor) {
        public Snapshot { cells = cells.clone(); }
        @Override public byte[] cells() { return cells.clone(); }
    }
    public record Span(int x, int y, byte[] cells) {
        public Span { cells = cells.clone(); }
        @Override public byte[] cells() { return cells.clone(); }
    }
    public record Delta(long base, long revision, List<Span> spans, Cursor cursor) {
        public Delta { spans = List.copyOf(spans); }
    }
    private final byte[] cells = new byte[CELLS];
    private final BitSet dirty = new BitSet(CELLS);
    private Cursor cursor = new Cursor(0, 0, false, false);
    private long revision, drained;
    public TtdBuffer() { Arrays.fill(cells, (byte) ' '); }
    public static boolean fits(int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && x < WIDTH && y < HEIGHT && width <= WIDTH - x && height <= HEIGHT - y;
    }
    public synchronized byte[] read(int x, int y, int width, int height) {
        if (!fits(x, y, width, height)) throw new IllegalArgumentException("Invalid display rectangle");
        byte[] bytes = new byte[width * height];
        for (int row = 0; row < height; row++) System.arraycopy(cells, (y + row) * WIDTH + x, bytes, row * width, width);
        return bytes;
    }
    public synchronized void write(int x, int y, int width, int height, byte[] bytes) {
        if (!fits(x, y, width, height) || bytes.length != width * height) throw new IllegalArgumentException("Invalid display write");
        boolean changed = false;
        for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
            int index = (y + row) * WIDTH + x + column;
            byte value = bytes[row * width + column];
            if (cells[index] != value) { cells[index] = value; dirty.set(index); changed = true; }
        }
        if (changed) revision++;
    }
    public synchronized void fill(int x, int y, int width, int height, byte value) {
        if (!fits(x, y, width, height)) throw new IllegalArgumentException("Invalid display rectangle");
        byte[] bytes = new byte[width * height]; Arrays.fill(bytes, value);
        write(x, y, width, height, bytes);
    }
    public synchronized void copy(int x, int y, int width, int height, int targetX, int targetY) {
        if (!fits(x, y, width, height) || !fits(targetX, targetY, width, height)) throw new IllegalArgumentException("Invalid display rectangle");
        write(targetX, targetY, width, height, read(x, y, width, height)); // Overlap behaves like memmove.
    }
    public synchronized void cursor(Cursor value) {
        if (!fits(value.x(), value.y(), 1, 1)) throw new IllegalArgumentException("Invalid cursor");
        if (!cursor.equals(value)) { cursor = value; revision++; }
    }
    public synchronized Snapshot snapshot() { return new Snapshot(revision, cells, cursor); }
    public synchronized Delta drain() {
        if (drained == revision) return null;
        List<Span> spans = new ArrayList<>();
        for (int y = 0; y < HEIGHT; y++) {
            int start = dirty.nextSetBit(y * WIDTH), end = (y + 1) * WIDTH;
            if (start < 0 || start >= end) continue;
            int last = dirty.previousSetBit(end - 1);
            spans.add(new Span(start % WIDTH, y, Arrays.copyOfRange(cells, start, last + 1)));
        }
        Delta result = new Delta(drained, revision, spans, cursor);
        dirty.clear(); drained = revision;
        return result;
    }
    public synchronized void restore(Snapshot snapshot) {
        byte[] data = snapshot.cells();
        if (data.length != CELLS || snapshot.revision() < 0 || !fits(snapshot.cursor().x(), snapshot.cursor().y(), 1, 1))
            throw new IllegalArgumentException("Invalid display snapshot");
        System.arraycopy(data, 0, cells, 0, CELLS);
        revision = drained = snapshot.revision(); cursor = snapshot.cursor(); dirty.clear();
    }
    /** Apply an ordered network delta atomically, rejecting gaps and malformed spans. */
    public synchronized boolean apply(Delta delta) {
        if (delta.base() != revision || delta.revision() <= revision || !fits(delta.cursor().x(), delta.cursor().y(), 1, 1)) return false;
        for (Span span : delta.spans()) if (!fits(span.x(), span.y(), span.cells().length, 1)) return false;
        for (Span span : delta.spans()) {
            byte[] bytes = span.cells(); System.arraycopy(bytes, 0, cells, span.y() * WIDTH + span.x(), bytes.length);
        }
        cursor = delta.cursor(); revision = drained = delta.revision(); return true;
    }
}
