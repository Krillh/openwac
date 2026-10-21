package org.mod.openwac.core.device.importer;

import java.util.UUID;

/** Host-owned selection of source files. Guest memory never provides filesystem paths. */
@FunctionalInterface
public interface FileImporterRepository {
    FileImporterDevice open(UUID id);
}
