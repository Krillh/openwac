package org.mod.openwac.integration.neoforge.blockentity;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import org.mod.openwac.core.device.Device;
import org.mod.openwac.core.device.ComponentContext;
import org.mod.openwac.core.device.PeripheralPortDevice;
import org.mod.openwac.core.device.eeprom.BiosEeprom;
import org.mod.openwac.integration.neoforge.peripheral.PeripheralEndpoint;
import org.mod.openwac.integration.neoforge.peripheral.PeripheralConnections;
import org.mod.openwac.core.machine.MachineState;
import org.mod.openwac.integration.neoforge.item.ComponentItem;
import org.mod.openwac.integration.neoforge.item.BiosEepromItem;
import org.mod.openwac.integration.neoforge.item.CpuItem;
import org.mod.openwac.integration.neoforge.item.RamItem;
import org.mod.openwac.integration.neoforge.item.PeripheralPortItem;
import org.mod.openwac.integration.neoforge.menu.ComputerCaseMenu;
import org.mod.openwac.integration.neoforge.registration.ModContent;
import org.mod.openwac.service.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.mod.openwac.integration.neoforge.storage.DiskLifecycle;
import org.mod.openwac.core.diagnostic.FailureDetails;
import org.mod.openwac.core.machine.MachineCrash;
import org.mod.openwac.core.machine.MachineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Inventory and lifecycle bridge. No guest code executes on this block entity's thread. */
public final class ComputerCaseBlockEntity extends BaseContainerBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(ComputerCaseBlockEntity.class);
    private final CrashReportPublisher crashReports = new CrashReportPublisher(report -> LOGGER.error("{}", report));
    public static final int COMPONENT_SLOTS = 9, BIOS_SLOT = 9, CPU_SLOT = 10, RAM_START = 11, RAM_SLOTS = 4, INVENTORY_SIZE = 15;
    private NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private UUID machineId = UUID.randomUUID();
    private boolean powered;
    private ComputerRuntime runtime;
    private CompletableFuture<Void> storageIdle = CompletableFuture.completedFuture(null);
    private Set<UUID> installedDisks = Set.of();
    private String startupFailure = "";
    private boolean peripheralPortInstalled;
    private record PeripheralAttachment(PeripheralEndpoint endpoint, UUID deviceId) {}
    private final Map<BlockPos, PeripheralAttachment> peripherals = new HashMap<>();
    private final Map<BlockPos, UUID> peripheralLinks = new LinkedHashMap<>();
    public UUID machineId() { return machineId; }
    /** Returns true when linked and false when an existing identical link was removed. */
    public boolean togglePeripheralLink(BlockPos pos, UUID id) {
        if (!PeripheralConnections.inRange(worldPosition, pos)) throw new IllegalArgumentException("Peripheral is too far away");
        if (id.equals(peripheralLinks.get(pos))) { peripheralLinks.remove(pos); setChanged(); return false; }
        if (!peripheralLinks.containsKey(pos) && peripheralLinks.size() >= PeripheralConnections.MAX_LINKS)
            throw new IllegalStateException("This computer already has 16 logical wire links");
        peripheralLinks.put(pos.immutable(), id); setChanged(); return true;
    }
    public int clearPeripheralLinks() {
        int count = peripheralLinks.size(); peripheralLinks.clear(); setChanged(); return count;
    }

    public ComputerCaseBlockEntity(BlockPos pos, BlockState state) { super(ModContent.COMPUTER_CASE_ENTITY.get(), pos, state); }
    @Override public int getContainerSize() { return INVENTORY_SIZE; }
    @Override public int getMaxStackSize() { return 1; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; reconcileDisks(); }
    private void reconcileDisks() {
        if (!(level instanceof ServerLevel server) || installedDisks == null) return;
        Set<UUID> current = DiskLifecycle.disks(this);
        if (current.equals(installedDisks)) return;
        Set<UUID> removed = new HashSet<>(installedDisks);
        removed.removeAll(current);
        installedDisks = current;
        if (!removed.isEmpty()) {
            // Forced inventory changes can also eject a disk while a turn is still finishing.
            stopRuntime();
            removed.forEach(id -> DiskLifecycle.ejected(server, id, storageIdle));
        }
    }
    @Override public void setChanged() { super.setChanged(); reconcileDisks(); }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = super.removeItemNoUpdate(slot);
        reconcileDisks();
        return removed;
    }
    @Override public void clearContent() { super.clearContent(); reconcileDisks(); }
    @Override public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        stopRuntime();
        super.preRemoveSideEffects(pos, state);
        reconcileDisks();
    }
    @Override protected Component getDefaultName() { return Component.translatable("container.openwac.computer_case"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new ComputerCaseMenu(id, inventory, this); }
    public static boolean acceptsItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= INVENTORY_SIZE) return false;
        if (slot >= RAM_START) return stack.getItem() instanceof RamItem;
        if (slot == CPU_SLOT) return stack.getItem() instanceof CpuItem;
        if (!(stack.getItem() instanceof ComponentItem)) return false;
        return slot == BIOS_SLOT ? stack.getItem() instanceof BiosEepromItem : !(stack.getItem() instanceof BiosEepromItem);
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return !powered && acceptsItem(slot, stack); }
    @Override public boolean canTakeItem(net.minecraft.world.Container into, int slot, ItemStack stack) { return !powered; }
    @Override public void setItem(int slot, ItemStack stack, boolean insideTransaction) {
        if (!stack.isEmpty() && !acceptsItem(slot, stack)) throw new IllegalArgumentException("RAM, CPUs and BIOS EEPROMs require their dedicated slots; other slots accept regular components");
        if (stack.getItem() instanceof ComponentItem) ComponentItem.deviceId(stack);
        super.setItem(slot, stack, insideTransaction);
    }
    public boolean isPowered() { return powered; }
    public void togglePower(Player player) {
        if (!stillValid(player) || !canOpen(player)) return;
        if (powered) { powered = false; stopRuntime(); }
        else { powered = true; startupFailure = ""; crashReports.reset(); }
        setChanged();
        player.sendSystemMessage(Component.translatable(powered ? "message.openwac.power_on" : "message.openwac.power_off"));
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, ComputerCaseBlockEntity computer) {
        if (!(level instanceof ServerLevel server) || !computer.powered) return;
        if (computer.runtime != null && computer.runtime.restartRequested()) {
            computer.powered = false;
            computer.stopRuntime();
            computer.powered = true;
            computer.startupFailure = "";
            computer.crashReports.reset();
            computer.setChanged();
            return;
        }
        if (computer.runtime == null && computer.startupFailure.isEmpty()) {
            if (!computer.storageIdle.isDone()) return;
            try { computer.startRuntime(server); }
            catch (RuntimeException failure) {
                var details = FailureDetails.capture("Computer startup", failure);
                computer.startupFailure = details.summary();
                computer.setChanged();
                computer.reportFailure(server, computer.startupReport(details));
            }
        }
        if (computer.runtime == null) return;
        computer.refreshPeripherals(server, null);
        computer.runtime.tick();
        MachineState runtimeState = computer.runtime.state();
        if (computer.runtime.restartRequested()) return;
        for (var failure : computer.runtime.drainKernelInstallFailures()) {
            LOGGER.warn("{}", failure.render(computer.machineId, server.dimension().identifier().toString(),
                    pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
            for (var player : server.players()) {
                if (player.blockPosition().distSqr(pos) <= 256 && computer.canOpen(player))
                    player.sendSystemMessage(Component.translatable("message.openwac.kernel_install_failed", failure.chatSummary()));
            }
        }
        boolean stopped = runtimeState == MachineState.CRASHED || runtimeState == MachineState.OFF;
        if (stopped || level.getGameTime() % 20 == 0) {
            List<String> messages = computer.runtime.drainChat();
            for (var player : server.players()) {
                if (player.blockPosition().distSqr(pos) > 256 || !computer.canOpen(player)) continue;
                for (String message : messages) player.sendSystemMessage(Component.literal("[OpenWAC] " + sanitize(message)));
            }
            if (runtimeState == MachineState.CRASHED) {
                computer.startupFailure = computer.runtime.diagnostic();
                computer.setChanged();
                computer.reportFailure(server, computer.runtime.crashReport());
                computer.stopRuntime();
            }
            else if (runtimeState == MachineState.OFF) {
                computer.powered = false;
                computer.stopRuntime();
                computer.setChanged();
            }
        }
    }
    private static String sanitize(String text) {
        return text.replaceAll("[\\p{Cntrl}§]", " ");
    }
    private ComputerCrashReport startupReport(FailureDetails failure) {
        ItemStack biosStack = items.get(BIOS_SLOT);
        String firmware = biosStack.getItem() instanceof BiosEepromItem bios ? bios.firmwareReference(biosStack) : "none installed";
        long memoryBytes = 0;
        for (int slot = RAM_START; slot < RAM_START + RAM_SLOTS; slot++) {
            if (items.get(slot).getItem() instanceof RamItem ram) memoryBytes += ram.capacityBytes();
        }
        MachineConfig config = null;
        if (items.get(CPU_SLOT).getItem() instanceof CpuItem cpu && memoryBytes > 0) {
            try { config = cpu.machineConfig(memoryBytes); }
            catch (IllegalArgumentException invalidLimits) { /* Preserve the original startup failure in the report. */ }
        }
        return new ComputerCrashReport(new MachineCrash(machineId, 0, 0, firmware, config, failure),
                0, memoryBytes / 65536, null);
    }
    private void reportFailure(ServerLevel level, ComputerCrashReport report) {
        if (report == null) report = startupReport(FailureDetails.message("Computer crash", startupFailure));
        if (!crashReports.publish(report, level.dimension().identifier().toString(),
                worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ())) return;
        for (var player : level.players()) {
            if (player.blockPosition().distSqr(worldPosition) <= 256 && canOpen(player))
                player.sendSystemMessage(Component.translatable("message.openwac.crash_report", sanitize(startupFailure)));
        }
    }
    private void startRuntime(ServerLevel level) {
        if (!(items.get(CPU_SLOT).getItem() instanceof CpuItem cpu))
            throw new IllegalStateException("Install a CPU in the dedicated CPU slot");
        if (!(items.get(BIOS_SLOT).getItem() instanceof BiosEepromItem))
            throw new IllegalStateException("Install a BIOS EEPROM in the dedicated BIOS slot");
        long installedMemory = 0;
        for (int slot = RAM_START; slot < RAM_START + RAM_SLOTS; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof RamItem ram) || stack.getCount() != 1)
                throw new IllegalStateException("Install one RAM module per dedicated RAM slot");
            installedMemory = Math.addExact(installedMemory, ram.capacityBytes());
        }
        if (installedMemory == 0) throw new IllegalStateException("Install RAM in the dedicated RAM slots");
        var config = cpu.machineConfig(installedMemory);
        var repository = new FileFirmwareRepository(level.getServer().getWorldPath(LevelResource.ROOT).resolve("openwac/firmware"),
                Map.of(FileFirmwareRepository.BOOTLOADER_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/bootloader-v1.wasm"),
                        FileFirmwareRepository.DEFAULT_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/bootstrap-v1.wasm"),
                        FileFirmwareRepository.DISK_TEST_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/disk-test-v1.wasm"),
                        FileFirmwareRepository.IMPORTER_TEST_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/importer-test-v1.wasm"),
                        FileFirmwareRepository.TTD_TEST_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/ttd-test-v1.wasm"),
                        FileFirmwareRepository.IMPORTER_TTD_TEST_BIOS, () -> ComputerCaseBlockEntity.class.getResourceAsStream("/data/openwac/firmware/importer-ttd-test-v1.wasm")));
        var context = new ComponentContext(repository, new FileStorageRepository(
                level.getServer().getWorldPath(LevelResource.ROOT).resolve("openwac/disks")),
                new ImportedFileStore(level.getServer().getWorldPath(LevelResource.ROOT).resolve("openwac/imports")));
        List<Device> devices = new ArrayList<>();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            if (stack.getCount() != 1) throw new IllegalStateException("Install only one item per computer slot");
            if (!acceptsItem(slot, stack)) throw new IllegalStateException("Install RAM, CPUs and BIOS EEPROMs only in their dedicated slots");
            if (slot == CPU_SLOT || slot >= RAM_START) continue;
            if (!(stack.getItem() instanceof ComponentItem component)) throw new IllegalStateException("Invalid component item");
            devices.add(component.createDevice(stack, context));
        }
        if (devices.stream().filter(device -> device instanceof BiosEeprom).count() != 1)
            throw new IllegalStateException("Install exactly one BIOS EEPROM");
        peripheralPortInstalled = devices.stream().anyMatch(device -> device instanceof PeripheralPortDevice);
        setChanged(); // Persist any lazily assigned device UUIDs.
        try {
            refreshPeripherals(level, devices);
            runtime = new ComputerRuntime(machineId, devices, config);
        } catch (RuntimeException failure) { releasePeripherals(); throw failure; }
    }
    /** Main-thread topology discovery never loads a neighboring chunk. Guest access uses core-only leases. */
    private void refreshPeripherals(Level level, List<Device> bootDevices) {
        // The port is a bus switch. External UUIDs are attached directly to the normal
        // DeviceBus; neither discovery nor invocation requires an extra guest-side hop.
        if (!peripheralPortInstalled || items.stream().noneMatch(stack -> stack.getItem() instanceof PeripheralPortItem)) {
            for (var attachment : peripherals.values()) {
                attachment.endpoint().releasePeripheral(machineId);
                if (runtime != null) runtime.detachDevice(attachment.deviceId());
            }
            peripherals.clear();
            return;
        }
        // Adjacency remains automatic. Logical links add bounded, identity-pinned candidates.
        Map<BlockPos, UUID> candidates = new LinkedHashMap<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = worldPosition.relative(direction);
            if (level.hasChunkAt(neighbor) && level.getBlockEntity(neighbor) instanceof PeripheralEndpoint endpoint)
                candidates.put(neighbor, endpoint.peripheralId());
        }
        peripheralLinks.forEach((pos, id) -> {
            if (PeripheralConnections.inRange(worldPosition, pos)) candidates.putIfAbsent(pos, id);
        });
        var iterator = peripherals.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var attachment = entry.getValue();
            if (!attachment.deviceId().equals(candidates.get(entry.getKey())) || !level.hasChunkAt(entry.getKey())
                    || level.getBlockEntity(entry.getKey()) != attachment.endpoint()
                    || !attachment.endpoint().peripheralId().equals(attachment.deviceId()) || !attachment.endpoint().ownedBy(machineId)) {
                attachment.endpoint().releasePeripheral(machineId);
                if (runtime != null) runtime.detachDevice(attachment.deviceId());
                iterator.remove();
            }
        }
        for (var candidate : candidates.entrySet()) {
            BlockPos neighbor = candidate.getKey();
            if (peripherals.containsKey(neighbor) || !level.hasChunkAt(neighbor)) continue;
            if (!(level.getBlockEntity(neighbor) instanceof PeripheralEndpoint endpoint) || !endpoint.peripheralId().equals(candidate.getValue())) continue;
            var claimed = endpoint.claimPeripheral(machineId);
            if (claimed.isEmpty()) continue;
            try {
                if (bootDevices != null) bootDevices.add(claimed.get()); else runtime.attachDevice(claimed.get());
                peripherals.put(neighbor, new PeripheralAttachment(endpoint, endpoint.peripheralId()));
            } catch (RuntimeException unavailable) { endpoint.releasePeripheral(machineId); }
        }
    }
    private void releasePeripherals() {
        peripherals.values().forEach(attachment -> attachment.endpoint().releasePeripheral(machineId));
        peripherals.clear();
    }
    private void stopRuntime() {
        releasePeripherals();
        peripheralPortInstalled = false;
        if (runtime != null) {
            storageIdle = CompletableFuture.allOf(storageIdle, runtime.stopped());
            runtime.close();
            runtime = null;
        }
    }
    @Override public void setRemoved() { stopRuntime(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { stopRuntime(); super.onChunkUnloaded(); }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("OpenWacVersion", 3);
        output.putString("MachineId", machineId.toString());
        output.putBoolean("Powered", powered);
        output.putString("Failure", startupFailure);
        ContainerHelper.saveAllItems(output, items);
        var links = output.childrenList("PeripheralLinks");
        peripheralLinks.forEach((pos, id) -> {
            var link = links.addChild();
            link.putInt("X", pos.getX()); link.putInt("Y", pos.getY()); link.putInt("Z", pos.getZ());
            link.putString("DeviceId", id.toString());
        });
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        stopRuntime();
        try { machineId = UUID.fromString(input.getStringOr("MachineId", "")); }
        catch (IllegalArgumentException invalid) { machineId = UUID.randomUUID(); }
        powered = input.getBooleanOr("Powered", false);
        startupFailure = input.getStringOr("Failure", "");
        items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        peripheralLinks.clear();
        int linksRead = 0;
        for (var link : input.childrenListOrEmpty("PeripheralLinks")) {
            if (++linksRead > PeripheralConnections.MAX_LINKS) break;
            BlockPos pos = new BlockPos(link.getIntOr("X", 0), link.getIntOr("Y", 0), link.getIntOr("Z", 0));
            try {
                UUID id = UUID.fromString(link.getStringOr("DeviceId", ""));
                if (PeripheralConnections.inRange(worldPosition, pos)) peripheralLinks.put(pos, id);
            } catch (IllegalArgumentException invalid) { /* Ignore malformed saved link records. */ }
        }
        if (input.getIntOr("OpenWacVersion", 1) < 2 && items.get(BIOS_SLOT).isEmpty()) {
            for (int slot = 0; slot < COMPONENT_SLOTS; slot++) {
                if (items.get(slot).getItem() instanceof BiosEepromItem) {
                    items.set(BIOS_SLOT, items.get(slot));
                    items.set(slot, ItemStack.EMPTY);
                    break;
                }
            }
        }
        installedDisks = DiskLifecycle.disks(this);
    }
}
