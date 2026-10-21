package org.mod.openwac.integration.neoforge.menu;

import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import org.mod.openwac.integration.neoforge.registration.ModContent;
import org.mod.openwac.integration.neoforge.item.BiosEepromItem;
import org.mod.openwac.integration.neoforge.item.CpuItem;
import org.mod.openwac.integration.neoforge.item.RamItem;
import org.mod.openwac.integration.neoforge.blockentity.ComputerCaseBlockEntity;

/** Dedicated BIOS, CPU and RAM slots and nine component slots; identical layout on both sides. */
public final class ComputerCaseMenu extends AbstractContainerMenu {
    private final ComputerCaseBlockEntity computer;
    private final Container container;
    private int powered;
    public ComputerCaseMenu(int id, Inventory inventory) { this(id, inventory, new SimpleContainer(ComputerCaseBlockEntity.INVENTORY_SIZE), null); }
    public ComputerCaseMenu(int id, Inventory inventory, ComputerCaseBlockEntity computer) {
        this(id, inventory, computer, computer);
    }
    private ComputerCaseMenu(int id, Inventory inventory, Container container, ComputerCaseBlockEntity computer) {
        super(ModContent.COMPUTER_CASE_MENU.get(), id);
        this.computer = computer;
        this.container = container;
        addDataSlot(new DataSlot() {
            @Override public int get() { return computer == null ? powered : computer.isPowered() ? 1 : 0; }
            @Override public void set(int value) { powered = value; }
        });
        addComponentSlot(ComputerCaseBlockEntity.BIOS_SLOT, 8, 22);
        addComponentSlot(ComputerCaseBlockEntity.CPU_SLOT, 116, 22);
        for (int slot = 0; slot < ComputerCaseBlockEntity.RAM_SLOTS; slot++)
            addComponentSlot(ComputerCaseBlockEntity.RAM_START + slot, 8 + slot * 18, 58);
        for (int slot = 0; slot < ComputerCaseBlockEntity.COMPONENT_SLOTS; slot++) addComponentSlot(slot, 8 + slot * 18, 94);
        addStandardInventorySlots(inventory, 8, 130);
    }
    private void addComponentSlot(int slot, int x, int y) {
        addSlot(new Slot(container, slot, x, y) {
            @Override public boolean mayPlace(ItemStack stack) { return !isPowered() && ComputerCaseBlockEntity.acceptsItem(slot, stack); }
            @Override public boolean mayPickup(Player player) { return !isPowered(); }
            @Override public int getMaxStackSize() { return 1; }
        });
    }
    public boolean isPowered() { return computer == null ? powered != 0 : computer.isPowered(); }
    @Override public boolean stillValid(Player player) {
        return computer == null ? container.stillValid(player) : computer.stillValid(player) && computer.canOpen(player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (isPowered() || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        int machineSlots = ComputerCaseBlockEntity.INVENTORY_SIZE;
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            int ramEnd = 2 + ComputerCaseBlockEntity.RAM_SLOTS;
            int start = stack.getItem() instanceof BiosEepromItem ? 0 : stack.getItem() instanceof CpuItem ? 1
                    : stack.getItem() instanceof RamItem ? 2 : ramEnd;
            int end = start < 2 ? start + 1 : start == 2 ? ramEnd : machineSlots;
            if (!moveItemStackTo(stack, start, end, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }
}
