package org.mod.openwac.integration.neoforge.peripheral;

import org.mod.openwac.core.device.Device;
import java.util.Optional;
import java.util.UUID;

/** World-block adapter for external hardware. Attachment policies never reach guest code. */
public interface PeripheralEndpoint {
    UUID peripheralId();
    Optional<Device> claimPeripheral(UUID computer);
    boolean ownedBy(UUID computer);
    void releasePeripheral(UUID computer);
}
