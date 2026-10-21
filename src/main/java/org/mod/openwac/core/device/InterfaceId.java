package org.mod.openwac.core.device;

import java.util.Objects;

/** Stable hardware interface identifier. Numeric IDs are part of the guest ABI. */
public record InterfaceId(int numericId, String name, int major, int minor) {
    public InterfaceId {
        if (numericId <= 0 || major < 1 || minor < 0) throw new IllegalArgumentException("Invalid interface version/id");
        Objects.requireNonNull(name, "name");
        if (!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Expected namespaced interface name");
    }
}
