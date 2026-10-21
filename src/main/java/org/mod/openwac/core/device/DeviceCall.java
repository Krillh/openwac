package org.mod.openwac.core.device;

import java.util.Arrays;

public record DeviceCall(int operation, byte[] arguments, int responseLimit) {
    public DeviceCall {
        if (arguments == null || responseLimit < 0 || (long) arguments.length + responseLimit > 256 * 1024)
            throw new IllegalArgumentException("Device call exceeds limits");
        arguments = Arrays.copyOf(arguments, arguments.length);
    }
    @Override public byte[] arguments() { return Arrays.copyOf(arguments, arguments.length); }
}
