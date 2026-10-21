package org.mod.openwac.core.device.eeprom;

import org.mod.openwac.core.device.*;
import org.mod.openwac.core.firmware.FirmwareRepository;
import java.io.IOException;
import java.nio.*;
import java.util.*;

/** BIOS media has permanent physical identity, independently of the shared immutable firmware. */
public final class BiosEeprom implements Device {
    public static final InterfaceId INTERFACE = new InterfaceId(1, "openwac:eeprom", 1, 0);
    private final UUID id;
    private final String reference;
    private final FirmwareRepository repository;
    private byte[] cachedImage;
    public BiosEeprom(UUID id, String reference, FirmwareRepository repository) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.repository = Objects.requireNonNull(repository);
    }
    @Override public UUID id() { return id; }
    @Override public InterfaceId interfaceId() { return INTERFACE; }
    private synchronized byte[] image() throws IOException {
        if (cachedImage == null) cachedImage = repository.load(reference);
        return cachedImage;
    }
    public byte[] bootloader() throws IOException { return image().clone(); }
    @Override public DeviceResult device_call(DeviceCall call) {
        byte[] arguments = call.arguments();
        try {
            return switch (call.operation()) {
                case 0 -> { // INFO: firmware length, maximum image size, read-only flag.
                    if (arguments.length != 0 || call.responseLimit() < 12) yield DeviceResult.error(-3);
                    yield DeviceResult.ok(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(image().length).putInt(FirmwareRepository.MAX_MODULE_BYTES).putInt(1).array());
                }
                case 1 -> { // READ: u32 offset, u32 length.
                    if (arguments.length != 8) yield DeviceResult.error(-3);
                    ByteBuffer input = ByteBuffer.wrap(arguments).order(ByteOrder.LITTLE_ENDIAN);
                    int offset = input.getInt(), length = input.getInt();
                    if (offset < 0 || length < 0 || length > call.responseLimit()) yield DeviceResult.error(-3);
                    byte[] image = image();
                    if ((long) offset + length > image.length) yield DeviceResult.error(-3);
                    yield DeviceResult.ok(Arrays.copyOfRange(image, offset, offset + length));
                }
                default -> DeviceResult.error(-3);
            };
        } catch (IOException unavailable) { return DeviceResult.error(-6); }
    }
    @Override public DeviceSnapshot snapshot() {
        return new DeviceSnapshot(1, id, "openwac:bios_eeprom", Map.of("firmware", reference));
    }
}
