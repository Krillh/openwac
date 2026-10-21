package org.mod.openwac.integration.neoforge.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.mod.openwac.core.device.display.TtdBuffer;
import org.mod.openwac.integration.neoforge.block.TtdBlock;
import org.mod.openwac.integration.neoforge.blockentity.TtdBlockEntity;

/** Direct atlas rendering batches all cells into one geometry submission, with no GPU resources per screen. */
public final class TtdRenderer implements BlockEntityRenderer<TtdBlockEntity, TtdRenderer.State> {
    public static final Identifier ATLAS = Identifier.fromNamespaceAndPath("openwac", "textures/display/cp437.png");
    public static final class State extends BlockEntityRenderState {
        byte[] cells = new byte[TtdBuffer.CELLS];
        Direction facing = Direction.NORTH;
        TtdBuffer.Cursor cursor = new TtdBuffer.Cursor(0, 0, false, false);
        boolean cursorShown, powered;
    }
    public static boolean showCursor(TtdBuffer.Cursor cursor) {
        return cursor.visible() && (!cursor.blink() || (System.nanoTime() / 500_000_000L & 1) == 0);
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(TtdBlockEntity display, State state, float partialTick, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay overlay) {
        BlockEntityRenderer.super.extractRenderState(display, state, partialTick, camera, overlay);
        TtdClientNetwork.ensureSnapshot(display);
        var snapshot = display.buffer().snapshot();
        state.cells = snapshot.cells(); state.cursor = snapshot.cursor(); state.cursorShown = showCursor(state.cursor);
        state.powered = display.isPowered();
        state.facing = display.getBlockState().getValue(TtdBlock.FACING);
    }
    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.powered) return; // The block model provides the unlit black panel.
        poses.pushPose(); poses.translate(0.5, 0.5, 0.5);
        float angle = switch (state.facing) { case SOUTH -> 180; case EAST -> -90; case WEST -> 90; default -> 0; };
        poses.rotateDegrees(Axis.YP, angle);
        // Capture immutable extracted values, not the mutable render-state holder.
        byte[] cells = state.cells; var cursor = state.cursor; boolean cursorShown = state.cursorShown;
        collector.submitCustomGeometry(poses, RenderTypes.entityTranslucentEmissive(ATLAS), (pose, vertices) -> {
            for (int y = 0; y < TtdBuffer.HEIGHT; y++) for (int x = 0; x < TtdBuffer.WIDTH; x++) {
                int code = Byte.toUnsignedInt(cells[y * TtdBuffer.WIDTH + x]);
                if (code == 32) continue;
                float u = (code & 31) / 32f, v = (code >>> 5) / 8f;
                quad(pose, vertices, x * 9, y * 16, 9, 16, -0.501f, u, v, u + 1 / 32f, v + 1 / 8f);
            }
            if (cursorShown) {
                // A single white texel from CP437 FULL BLOCK (0xDB) makes the underline.
                float u = (27 * 9 + 4.5f) / 288f, v = (6 * 16 + 8.5f) / 128f;
                quad(pose, vertices, cursor.x() * 9, cursor.y() * 16 + 14, 9, 2, -0.502f, u, v, u, v);
            }
        });
        poses.popPose();
    }
    private static void quad(PoseStack.Pose pose, VertexConsumer vertices, float x, float y, float width, float height, float z,
                             float u0, float v0, float u1, float v1) {
        float scale = 0.875f / Math.max(TtdBuffer.PIXEL_WIDTH, TtdBuffer.PIXEL_HEIGHT);
        float halfWidth = TtdBuffer.PIXEL_WIDTH * scale / 2;
        float halfHeight = TtdBuffer.PIXEL_HEIGHT * scale / 2;
        float left = halfWidth - x * scale, right = halfWidth - (x + width) * scale;
        float top = halfHeight - y * scale, bottom = halfHeight - (y + height) * scale;
        vertex(pose, vertices, left, top, z, u0, v0); vertex(pose, vertices, left, bottom, z, u0, v1);
        vertex(pose, vertices, right, bottom, z, u1, v1); vertex(pose, vertices, right, top, z, u1, v0);
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float x, float y, float z, float u, float v) {
        vertices.addVertex(pose, x, y, z).setColor(0xffffffff).setUv(u, v).setOverlay(0x000a0000).setLight(0x00f000f0).setNormal(pose, 0, 0, -1);
    }
}
