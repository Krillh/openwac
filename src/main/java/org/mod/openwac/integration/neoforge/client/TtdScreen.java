package org.mod.openwac.integration.neoforge.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mod.openwac.core.device.display.TtdBuffer;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;
import java.util.UUID;

/** A read-only enlarged view. Input devices are separate hardware interfaces. */
public final class TtdScreen extends Screen {
    private final BlockPos pos;
    private final UUID id;
    public TtdScreen(BlockPos pos, UUID id) { super(Component.translatable("block.openwac.ttd")); this.pos = pos; this.id = id; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
    }
    private TtdBlockEntity display() {
        if (minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof TtdBlockEntity display
                && display.device().id().equals(id)) return display;
        return null;
    }
    @Override public void tick() {
        var display = display();
        if (display == null || minecraft.player == null || minecraft.player.blockPosition().distSqr(pos) > 64) { onClose(); return; }
        TtdClientNetwork.ensureSnapshot(display);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, 0xffffffff);
        var display = display(); if (display == null) return;
        float scale = Math.min((width - 24) / (float) TtdBuffer.PIXEL_WIDTH, (height - 64) / (float) TtdBuffer.PIXEL_HEIGHT);
        // Prefer integral magnification when there is enough room; fractional reduction fits small GUIs.
        if (scale >= 1) scale = (float)Math.floor(scale);
        drawDisplay(graphics, display.buffer().snapshot(), display.isPowered(), (width - TtdBuffer.PIXEL_WIDTH * scale) / 2, 26, scale);
    }
    public static void drawDisplay(GuiGraphicsExtractor graphics, TtdBuffer.Snapshot snapshot, boolean powered, float left, float top, float scale) {
        if (scale <= 0) return;
        byte[] cells = snapshot.cells();
        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);
        graphics.fill(-2, -2, TtdBuffer.PIXEL_WIDTH + 2, TtdBuffer.PIXEL_HEIGHT + 2, 0xff555555);
        graphics.fill(0, 0, TtdBuffer.PIXEL_WIDTH, TtdBuffer.PIXEL_HEIGHT, 0xff000000);
        if (!powered) { graphics.pose().popMatrix(); return; }
        for (int y = 0; y < TtdBuffer.HEIGHT; y++) for (int x = 0; x < TtdBuffer.WIDTH; x++) {
            int code = Byte.toUnsignedInt(cells[y * TtdBuffer.WIDTH + x]);
            if (code == 32) continue;
            graphics.blit(RenderPipelines.GUI_TEXTURED, TtdRenderer.ATLAS, x * 9, y * 16,
                    (code & 31) * 9, (code >>> 5) * 16, 9, 16, 288, 128);
        }
        var cursor = snapshot.cursor();
        if (TtdRenderer.showCursor(cursor)) graphics.fill(cursor.x() * 9, cursor.y() * 16 + 14, cursor.x() * 9 + 9, cursor.y() * 16 + 16, 0xffffffff);
        graphics.pose().popMatrix();
    }
}
