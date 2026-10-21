package org.mod.openwac.core.device;

import java.util.Arrays;

public record DeviceResult(int code, byte[] response) {
    public DeviceResult {
        if (code > 0 || (code < 0 && response != null && response.length != 0))
            throw new IllegalArgumentException("A result must contain either response bytes or a negative error");
        response = Arrays.copyOf(response == null ? new byte[0] : response, response == null ? 0 : response.length);
        if (response.length > 256 * 1024) throw new IllegalArgumentException("Device response exceeds limit");
    }
    @Override public byte[] response() { return Arrays.copyOf(response, response.length); }
    public static DeviceResult ok(byte[] bytes) { return new DeviceResult(0, bytes); }
    public static DeviceResult error(int code) { return new DeviceResult(code, new byte[0]); }
}
