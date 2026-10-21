package org.mod.openwac.core.device;

import java.util.Map;
import java.util.UUID;

public record DeviceSnapshot(int schemaVersion, UUID id, String type, Map<String, String> metadata) {
    public DeviceSnapshot { metadata = Map.copyOf(metadata); }
}
