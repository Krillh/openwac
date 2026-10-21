package org.mod.openwac.core.device;

/** Shared wire status values from true_device_interfaces.md. */
public final class DeviceStatus {
    private DeviceStatus() {}
    public static final int OK = 0, UNKNOWN_ERROR = -1, INVALID_OPERATION = -2,
            INVALID_ARGUMENT = -3, ARGUMENT_OVERRUN = -4, RESPONSE_OVERRUN = -5,
            DEVICE_BROKEN = -6, DEVICE_NOT_AVAILABLE = -7, POINTER_INVALID = -8,
            MEMORY_INVALID = -9, OUT_OF_BOUNDS = -10, SYSCALL_TOO_EXPENSIVE = -11,
            PERMISSION_DENIED = -12;
}
