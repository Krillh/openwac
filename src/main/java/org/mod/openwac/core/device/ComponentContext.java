package org.mod.openwac.core.device;

import org.mod.openwac.core.device.storage.StorageRepository;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.core.device.importer.FileImporterRepository;
import org.mod.openwac.core.device.importer.FileImporterDevice;
import java.util.Objects;

/** Server-provided backing services for component instances, without Minecraft types. */
public record ComponentContext(FirmwareRepository firmware, StorageRepository storage, FileImporterRepository importers) {
    public ComponentContext(FirmwareRepository firmware, StorageRepository storage) {
        this(firmware, storage, id -> new FileImporterDevice(id, (java.nio.file.Path) null));
    }
    public ComponentContext {
        Objects.requireNonNull(firmware);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(importers);
    }
}
