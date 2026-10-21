package org.mod.openwac.integration.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.mod.openwac.core.device.input.KeyboardDevice;
import org.mod.openwac.integration.neoforge.blockentity.KeyboardBlockEntity;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;
import org.mod.openwac.core.device.display.TtdBuffer;
import org.mod.openwac.integration.neoforge.network.KeyboardPayloads;
import java.util.*;

/** Keyboard session with optional display viewing and a temporary walking mode. */
public final class KeyboardCaptureScreen extends Screen {
    private final UUID token, device;
    private final BlockPos pos;
    private final BitSet held = new BitSet(512);
    private final int[] heldKeycodes = new int[512];
    private int modifiers, ticks;
    private boolean closed, suspended, captureEscape, captureAlt, altReleased;
    private BlockPos displayPos;
    private UUID displayId;
    private Button worldButton, escapeButton, altButton;
    public KeyboardCaptureScreen(UUID token, BlockPos pos, UUID device) {
        super(Component.translatable("screen.openwac.keyboard.title")); this.token = token; this.pos = pos; this.device = device;
    }
    @Override protected void init() {
        minecraft.textInputManager().startTextInput(this);
        addRenderableWidget(Button.builder(Component.translatable("screen.openwac.keyboard.release"), button -> onClose())
                .bounds(width / 2 - 146, height - 30, 140, 20).build());
        worldButton = addRenderableWidget(Button.builder(Component.translatable("screen.openwac.keyboard.world"), button -> {
            displayPos = null; displayId = null; worldButton.visible = false;
        }).bounds(width / 2 + 6, height - 30, 140, 20).build());
        worldButton.visible = displayPos != null;
        escapeButton = addRenderableWidget(Button.builder(escapeLabel(), button -> {
            releaseHeld();
            captureEscape = !captureEscape;
            captureAlt = captureEscape;
            escapeButton.setMessage(escapeLabel());
            altButton.setMessage(altLabel());
        }).bounds(width / 2 - 146, height - 54, 140, 20).build());
        altButton = addRenderableWidget(Button.builder(altLabel(), button -> {
            releaseHeld(); captureAlt = !captureAlt; altButton.setMessage(altLabel());
        }).bounds(width / 2 + 6, height - 54, 140, 20).build());
    }
    private Component escapeLabel() { return Component.translatable("screen.openwac.keyboard.escape", Component.translatable(captureEscape ? "options.on" : "options.off")); }
    private Component altLabel() { return Component.translatable("screen.openwac.keyboard.alt", Component.translatable(captureAlt ? "options.on" : "options.off")); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public boolean shouldCloseOnEsc() { return !captureEscape; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { /* Keep the world unobscured. */ }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        var hints = font.split(Component.translatable("screen.openwac.keyboard.hint"), width - 16);
        graphics.fill(0, 0, width, 18 + hints.size() * 10, 0xa0000000);
        graphics.centeredText(font, title, width / 2, 5, 0xffffffff);
        int y = 18;
        for (var hint : hints) { graphics.centeredText(font, hint, width / 2, y, 0xffffffff); y += 10; }
        if (displayPos != null && minecraft.level != null && minecraft.level.getBlockEntity(displayPos) instanceof TtdBlockEntity display) {
            float scale = Math.min((width - 24) / (float) TtdBuffer.PIXEL_WIDTH, (height - y - 76) / (float) TtdBuffer.PIXEL_HEIGHT);
            if (scale >= 1) scale = (float)Math.floor(scale);
            TtdScreen.drawDisplay(graphics, display.buffer().snapshot(), display.isPowered(), (width - TtdBuffer.PIXEL_WIDTH * scale) / 2, y + 6, scale);
        }
    }
    private static int normalizedModifiers(int raw) {
        return ((raw & InputConstants.MOD_SHIFT) != 0 ? 1 : 0)
                | ((raw & InputConstants.MOD_CONTROL) != 0 ? 2 : 0)
                | ((raw & InputConstants.MOD_ALT) != 0 ? 4 : 0)
                | ((raw & InputConstants.MOD_SUPER) != 0 ? 8 : 0)
                | ((raw & InputConstants.MOD_CAPS_LOCK) != 0 ? 16 : 0)
                | ((raw & InputConstants.MOD_NUM_LOCK) != 0 ? 32 : 0);
    }
    private void send(int kind, int scancode, int keycode, int codepoint) {
        if (!closed && Minecraft.getInstance().getConnection() != null)
            ClientPacketDistributor.sendToServer(new KeyboardPayloads.Input(token, kind, scancode, keycode, modifiers, codepoint));
    }
    private void releaseHeld() {
        modifiers = 0;
        for (int scan = held.nextSetBit(0); scan >= 0; scan = held.nextSetBit(scan + 1))
            send(KeyboardDevice.KEY_UP, scan, heldKeycodes[scan], 0);
        held.clear();
    }
    private static boolean isAlt(int key) { return key == InputConstants.KEY_LALT || key == InputConstants.KEY_RALT; }
    @Override public boolean keyPressed(KeyEvent event) {
        int scan = event.key(); modifiers = normalizedModifiers(event.modifiers());
        if (scan == InputConstants.KEY_ESCAPE && !captureEscape) { onClose(); return true; }
        if (isAlt(scan) && !captureAlt) { suspend(); return true; }
        if (scan >= 0 && scan < 512) {
            send(held.get(scan) ? KeyboardDevice.KEY_REPEAT : KeyboardDevice.KEY_DOWN, scan, event.keycode(), 0);
            held.set(scan);
            heldKeycodes[scan] = event.keycode();
        }
        return true;
    }
    @Override public boolean keyReleased(KeyEvent event) {
        int scan = event.key(); modifiers = normalizedModifiers(event.modifiers());
        if (scan >= 0 && scan < 512 && held.get(scan)) { held.clear(scan); send(KeyboardDevice.KEY_UP, scan, event.keycode(), 0); }
        return true;
    }
    @Override public boolean charTyped(CharacterEvent event) {
        int code = event.codepoint();
        if (code > 0 && Character.isValidCodePoint(code) && !(code >= 0xd800 && code <= 0xdfff)) send(KeyboardDevice.TEXT, 0, 0, code);
        return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (displayPos != null) return true; // No clicks through the enlarged display into the world.
        if ((event.button() == InputConstants.MOUSE_BUTTON_LEFT || event.button() == InputConstants.MOUSE_BUTTON_RIGHT)
                && minecraft.level != null && minecraft.player != null) {
            // Project the UI cursor into the actual world, rather than relying on the frozen crosshair hit.
            var camera = minecraft.gameRenderer.mainCamera();
            Vec3 direction = camera.getNearPlane(camera.getFov()).getPointOnPlane(
                    (float)(event.x() / width * 2 - 1), (float)(1 - event.y() / height * 2)).normalize();
            Vec3 origin = camera.position();
            var hit = minecraft.level.clip(new ClipContext(origin, origin.add(direction.scale(8)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                if (hit.getBlockPos().equals(pos)) onClose();
                else if (minecraft.player.blockPosition().distSqr(hit.getBlockPos()) <= 64
                        && minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof TtdBlockEntity display) {
                    showDisplay(hit.getBlockPos(), display.device().id());
                }
            }
        }
        return true;
    }
    public void showDisplay(BlockPos pos, UUID id) {
        displayPos = pos; displayId = id;
        if (worldButton != null) worldButton.visible = pos != null;
        var client = Minecraft.getInstance();
        if (pos != null && client.level != null && client.level.getBlockEntity(pos) instanceof TtdBlockEntity display
                && display.device().id().equals(id)) {
            display.needsSnapshot(true); TtdClientNetwork.ensureSnapshot(display);
        }
    }
    private void suspend() {
        releaseHeld(); suspended = true; altReleased = false;
        minecraft.gui.setScreen(null);
        minecraft.gui.hud.setOverlayMessage(Component.translatable("screen.openwac.keyboard.suspended"), false);
    }
    public void resume() {
        if (closed) return;
        suspended = false;
        var client = Minecraft.getInstance();
        if (client.gui.screen() != this) client.gui.setScreen(this);
    }
    public void worldKey(int key, int action) {
        if (closed || !suspended || !isAlt(key)) return;
        if (action == InputConstants.RELEASE) altReleased = true;
        else if (action == InputConstants.PRESS && altReleased && Minecraft.getInstance().gui.screen() == null) resume();
    }
    public boolean matches(UUID session) { return !closed && token.equals(session); }
    public boolean isClosed() { return closed; }
    public void tickSession() {
        if (closed) return;
        if (minecraft.player == null || minecraft.level == null || !minecraft.isWindowActive()
                || minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 64
                || !(minecraft.level.getBlockEntity(pos) instanceof KeyboardBlockEntity keyboard) || !keyboard.peripheralId().equals(device)) {
            onClose(); return;
        }
        if (++ticks % 20 == 0 && minecraft.getConnection() != null) ClientPacketDistributor.sendToServer(new KeyboardPayloads.Heartbeat(token));
        if (displayPos != null) {
            if (minecraft.level.getBlockEntity(displayPos) instanceof TtdBlockEntity display && display.device().id().equals(displayId))
                TtdClientNetwork.ensureSnapshot(display);
            else { showDisplay(null, null); }
        }
    }
    public void serverClosed(KeyboardPayloads.Closed packet) {
        if (!token.equals(packet.token())) return;
        endSession(false);
        if (!packet.reason().isEmpty() && minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal("[OpenWAC] " + packet.reason()));
        if (minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
    }
    private void endSession(boolean notifyServer) {
        if (closed) return;
        if (notifyServer) {
            releaseHeld();
            if (Minecraft.getInstance().getConnection() != null) ClientPacketDistributor.sendToServer(new KeyboardPayloads.Stop(token));
        }
        closed = true; suspended = false; held.clear();
    }
    @Override public void onClose() {
        endSession(true);
        var client = Minecraft.getInstance();
        if (client.gui.screen() == this) client.gui.setScreen(null);
    }
    @Override public void removed() {
        minecraft.textInputManager().stopTextInput(this);
        if (!suspended) endSession(true);
        super.removed();
    }
}
