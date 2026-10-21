package org.mod.openwac.core.firmware;

import java.io.IOException;

/** Immutable shared firmware blobs. An EEPROM contains a reference, not a private copy. */
public interface FirmwareRepository {
    int MAX_MODULE_BYTES = 1024 * 1024;
    byte[] load(String reference) throws IOException;
    String install(byte[] wasm) throws IOException;
}
