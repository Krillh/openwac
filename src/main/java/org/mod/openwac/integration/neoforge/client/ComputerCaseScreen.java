package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.mod.openwac.integration.neoforge.menu.ComputerCaseMenu;

/** Dedicated BIOS, CPU and RAM slots, with server-synchronized power locking. */
public final class ComputerCaseScreen extends AbstractContainerScreen<ComputerCaseMenu> {
    public ComputerCaseScreen(ComputerCaseMenu menu, Inventory inventory, Component title) { super(menu, inventory, title, 176, 212); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xff373737);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffc6c6c6);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xffffffff);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xff373737);
            graphics.fill(x, y, x + 16, y + 16, 0xff8b8b8b);
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("container.openwac.bios_slot"), 30, 23, 0xff404040, false);
        graphics.text(font, Component.translatable("container.openwac.cpu_slot"), 138, 23, 0xff404040, false);
        graphics.text(font, Component.translatable("container.openwac.ram_slots"), 8, 46, 0xff404040, false);
        graphics.text(font, Component.translatable("container.openwac.components"), 8, 82, 0xff404040, false);
        if (menu.isPowered()) graphics.text(font, Component.translatable("container.openwac.power_locked"), 76, 82, 0xff8b2020, false);
    }
}
