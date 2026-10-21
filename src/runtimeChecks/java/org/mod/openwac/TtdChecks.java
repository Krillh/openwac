package org.mod.openwac;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.device.display.*;
import java.nio.*;
import java.util.*;

/** Checks geometry, full-sized device calls, and synchronization at the display edges. */
final class TtdChecks {
    static void run() {
        var buffer = new TtdBuffer();
        var display = new TtdDevice(UUID.randomUUID(), buffer);
        Device lease = display.claim(UUID.randomUUID()).orElseThrow();
        var geometry = ByteBuffer.wrap(lease.device_call(new DeviceCall(TtdDevice.GEOMETRY, new byte[0], 8)).response())
                .order(ByteOrder.LITTLE_ENDIAN);
        require(geometry.getInt() == 60 && geometry.getInt() == 30, "TTD must report 60 by 30 characters");
        require(TtdBuffer.PIXEL_WIDTH == 540 && TtdBuffer.PIXEL_HEIGHT == 480, "TTD logical resolution");
        byte[] cells = new byte[1800];
        for (int i = 0; i < cells.length; i++) cells[i] = (byte) i;
        byte[] write = ByteBuffer.allocate(16 + cells.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(0).putInt(60).putInt(30).put(cells).array();
        require(lease.device_call(new DeviceCall(TtdDevice.WRITE_CELLS, write, 0)).code() == 0, "Full-screen write must fit");
        byte[] rect = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(60).putInt(30).array();
        require(Arrays.equals(cells, lease.device_call(new DeviceCall(TtdDevice.READ_CELLS, rect, 1800)).response()),
                "Full-screen read must preserve all cells");
        require(buffer.snapshot().cells().length == 1800, "Snapshot must hold 1800 cells");
        var delta = buffer.drain();
        require(delta.spans().size() == 30 && delta.spans().stream().allMatch(span -> span.cells().length == 60),
                "Full-screen delta must contain 30 rows of 60 cells");
        var client = new TtdBuffer();
        require(client.apply(delta) && Arrays.equals(client.snapshot().cells(), cells), "Full-screen delta must synchronize");
        buffer.write(59, 29, 1, 1, new byte[] {42});
        buffer.cursor(new TtdBuffer.Cursor(59, 29, true, false));
        require(client.apply(buffer.drain()), "Bottom-right cell and cursor must synchronize");
        require(client.read(59, 29, 1, 1)[0] == 42 && client.snapshot().cursor().x() == 59,
                "Bottom-right cell and cursor must be retained");
        var restored = new TtdBuffer();
        restored.restore(buffer.snapshot());
        require(Arrays.equals(restored.snapshot().cells(), buffer.snapshot().cells()), "New-size snapshots must restore");
        for (int[] invalid : new int[][] {{60, 29, 1, 1}, {59, 30, 1, 1}, {59, 29, 2, 1}, {59, 29, 1, 2}}) {
            byte[] args = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(invalid[0]).putInt(invalid[1]).putInt(invalid[2]).putInt(invalid[3]).array();
            require(lease.device_call(new DeviceCall(TtdDevice.READ_CELLS, args, 1800)).code() == DeviceStatus.OUT_OF_BOUNDS,
                    "Rectangles beyond the new display edge must fail");
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
