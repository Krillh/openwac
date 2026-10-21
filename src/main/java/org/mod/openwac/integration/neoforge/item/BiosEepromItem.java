package org.mod.openwac.integration.neoforge.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.mod.openwac.core.device.Device;
import org.mod.openwac.core.device.ComponentContext;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.core.firmware.FirmwareRepository;
import org.mod.openwac.service.FileFirmwareRepository;
import java.io.IOException;

public final class BiosEepromItem extends ComponentItem {
    private static final String FIRMWARE_KEY = "openwac_firmware";
    private final String defaultFirmware;
    public BiosEepromItem(Properties properties) { this(properties, FileFirmwareRepository.DEFAULT_BIOS); }
    public BiosEepromItem(Properties properties, String defaultFirmware) {
        super(properties);
        this.defaultFirmware = java.util.Objects.requireNonNull(defaultFirmware);
    }
    @Override public Device createDevice(ItemStack stack, ComponentContext context) {
        return new BiosEeprom(deviceId(stack), firmwareReference(stack), context.firmware());
    }
    public String firmwareReference(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getStringOr(FIRMWARE_KEY, defaultFirmware);
    }
    /** Host-side programming entry point. The guest cannot select host files. */
    public void program(ItemStack stack, byte[] wasm, FirmwareRepository repository) throws IOException {
        if (stack.getItem() != this) throw new IllegalArgumentException("Not a BIOS EEPROM");
        deviceId(stack);
        String reference = repository.install(wasm);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(FIRMWARE_KEY, reference));
    }
}
