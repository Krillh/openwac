package org.mod.openwac.integration.neoforge.registration;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
import org.mod.openwac.Openwac;
import org.mod.openwac.integration.neoforge.block.ComputerCaseBlock;
import org.mod.openwac.integration.neoforge.blockentity.ComputerCaseBlockEntity;
import org.mod.openwac.integration.neoforge.block.TtdBlock;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;
import org.mod.openwac.integration.neoforge.item.TtdBlockItem;
import org.mod.openwac.integration.neoforge.item.BiosEepromItem;
import org.mod.openwac.integration.neoforge.item.CpuItem;
import org.mod.openwac.integration.neoforge.item.RamItem;
import org.mod.openwac.integration.neoforge.item.DiskDriveItem;
import org.mod.openwac.integration.neoforge.item.FileImporterItem;
import org.mod.openwac.integration.neoforge.item.PeripheralPortItem;
import org.mod.openwac.integration.neoforge.item.PeripheralWireItem;
import org.mod.openwac.integration.neoforge.block.KeyboardBlock;
import org.mod.openwac.integration.neoforge.blockentity.KeyboardBlockEntity;
import org.mod.openwac.integration.neoforge.item.KeyboardBlockItem;
import org.mod.openwac.integration.neoforge.menu.ComputerCaseMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import org.mod.openwac.service.FileFirmwareRepository;
import java.util.Set;

public final class ModContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Openwac.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Openwac.MODID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Openwac.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Openwac.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Openwac.MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<ComputerCaseMenu>> COMPUTER_CASE_MENU =
            MENUS.register("computer_case", () -> new MenuType<>(ComputerCaseMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final DeferredBlock<ComputerCaseBlock> COMPUTER_CASE = BLOCKS.registerBlock("computer_case", ComputerCaseBlock::new,
            properties -> properties.strength(3.5F));
    public static final DeferredItem<BlockItem> COMPUTER_CASE_ITEM = ITEMS.registerSimpleBlockItem(COMPUTER_CASE);
    public static final DeferredBlock<TtdBlock> TTD = BLOCKS.registerBlock("ttd", TtdBlock::new, properties -> properties.strength(2.5F));
    public static final DeferredItem<TtdBlockItem> TTD_ITEM = ITEMS.registerItem("ttd", properties -> new TtdBlockItem(TTD.get(), properties.useBlockDescriptionPrefix()));
    public static final DeferredBlock<KeyboardBlock> KEYBOARD = BLOCKS.registerBlock("keyboard", KeyboardBlock::new,
            properties -> properties.strength(2.0F).noOcclusion());
    public static final DeferredItem<KeyboardBlockItem> KEYBOARD_ITEM = ITEMS.registerItem("keyboard", properties -> new KeyboardBlockItem(KEYBOARD.get(), properties.useBlockDescriptionPrefix()));
    public static final DeferredItem<BiosEepromItem> BIOS_EEPROM = ITEMS.registerItem("bios_eeprom", BiosEepromItem::new);
    public static final DeferredItem<BiosEepromItem> BOOTLOADER_BIOS_EEPROM = ITEMS.registerItem("bootloader_bios_eeprom",
            properties -> new BiosEepromItem(properties, FileFirmwareRepository.BOOTLOADER_BIOS));
    public static final DeferredItem<CpuItem> CPU_TIER_1 = ITEMS.registerItem("cpu_tier_1",
            properties -> new CpuItem(properties, 65_536, 16));
    public static final DeferredItem<RamItem> RAM_TIER_1 = ITEMS.registerItem("ram_tier_1",
            properties -> new RamItem(properties, 1024L * 1024));
    public static final DeferredItem<BiosEepromItem> DISK_TEST_BIOS_EEPROM = ITEMS.registerItem("disk_test_bios_eeprom",
            properties -> new BiosEepromItem(properties, FileFirmwareRepository.DISK_TEST_BIOS));
    public static final DeferredItem<DiskDriveItem> DISK_DRIVE_TIER_1 = ITEMS.registerItem("disk_drive_tier_1", DiskDriveItem::new);
    public static final DeferredItem<BiosEepromItem> IMPORTER_TEST_BIOS_EEPROM = ITEMS.registerItem("importer_test_bios_eeprom",
            properties -> new BiosEepromItem(properties, FileFirmwareRepository.IMPORTER_TEST_BIOS));
    public static final DeferredItem<FileImporterItem> FILE_IMPORTER = ITEMS.registerItem("file_importer", FileImporterItem::new);
    public static final DeferredItem<PeripheralPortItem> PERIPHERAL_PORT = ITEMS.registerItem("peripheral_port", PeripheralPortItem::new);
    public static final DeferredItem<PeripheralWireItem> PERIPHERAL_WIRE = ITEMS.registerItem("peripheral_wire", PeripheralWireItem::new);
    public static final DeferredItem<BiosEepromItem> TTD_TEST_BIOS_EEPROM = ITEMS.registerItem("ttd_test_bios_eeprom",
            properties -> new BiosEepromItem(properties, FileFirmwareRepository.TTD_TEST_BIOS));
    public static final DeferredItem<BiosEepromItem> IMPORTER_TTD_TEST_BIOS_EEPROM = ITEMS.registerItem("importer_ttd_test_bios_eeprom",
            properties -> new BiosEepromItem(properties, FileFirmwareRepository.IMPORTER_TTD_TEST_BIOS));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> OPENWAC_TAB = CREATIVE_TABS.register("openwac", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.openwac"))
            .icon(() -> COMPUTER_CASE_ITEM.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .displayItems((parameters, output) -> ITEMS.getEntries().forEach(item -> {
                if (item.get().isEnabled(parameters.enabledFeatures())) output.accept(item.get());
            }))
            .build());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerCaseBlockEntity>> COMPUTER_CASE_ENTITY =
            BLOCK_ENTITIES.register("computer_case", () -> new BlockEntityType<>(ComputerCaseBlockEntity::new, Set.of(COMPUTER_CASE.get())));
    private ModContent() {}
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TtdBlockEntity>> TTD_ENTITY =
            BLOCK_ENTITIES.register("ttd", () -> new BlockEntityType<>(TtdBlockEntity::new, Set.of(TTD.get())));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KeyboardBlockEntity>> KEYBOARD_ENTITY =
            BLOCK_ENTITIES.register("keyboard", () -> new BlockEntityType<>(KeyboardBlockEntity::new, Set.of(KEYBOARD.get())));
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        CREATIVE_TABS.register(bus);
    }
}
