package org.mod.openwac.integration.neoforge.peripheral;

import net.minecraft.core.BlockPos;

/** Temporary logical-wire policy, separate from HAL addresses and device protocols. */
public final class PeripheralConnections {
    public static final int MAX_DISTANCE = 16, MAX_LINKS = 16;
    private PeripheralConnections() {}
    public static boolean inRange(BlockPos computer, BlockPos peripheral) {
        return computer.distSqr(peripheral) <= (double)MAX_DISTANCE * MAX_DISTANCE;
    }
}
