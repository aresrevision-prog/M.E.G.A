package dev.mega.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.mega.client.MegaClient.Target;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.util.List;

/** Draws tracer lines from the crosshair to targets, plus a box outline round each target. Visible through walls. */
public final class MegaTracers {
    private static final int YELLOW = 0xFFFFD400;
    private static final int BLACK = 0xFF000000;
    private static final int INVISIBLE_COLOR = 0xFFFF55FF;
    private static final float STRIPE = 0.75f; // blocks per stripe on player lines

    /** Vanilla line shader, but with depth test off so lines show through blocks. */
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
        .withLocation(Identifier.of(MegaClient.MOD_ID, "pipeline/esp_lines"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .build();

    private static final RenderLayer LAYER = RenderLayer.of("mega_client_esp_lines",
        RenderSetup.builder(PIPELINE).build());

    /** Same lines but hidden behind blocks that are drawn: in X-ray, outlines don't show through ores/stash blocks. */
    private static final RenderPipeline DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
        .withLocation(Identifier.of(MegaClient.MOD_ID, "pipeline/xray_lines"))
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withDepthWrite(false)
        .build();
    private static final RenderLayer DEPTH_LAYER = RenderLayer.of("mega_client_xray_lines",
        RenderSetup.builder(DEPTH_PIPELINE).build());
    /** A block, a hair bigger, so its outline isn't hidden by the block's own faces. */
    private static final net.minecraft.util.shape.VoxelShape CUBE_OUT = VoxelShapes.cuboid(-0.003, -0.003, -0.003, 1.003, 1.003, 1.003);

    private MegaTracers() {}

    /** Called from WorldRendererMixin at the end of the main world pass (same point Fabric API calls END_MAIN). */
    public static void renderWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) return;
        Vec3d cam = ((dev.mega.client.mixin.CameraAccessor) client.gameRenderer.getCamera()).mega$getPos();
        render(new MatrixStack(), client.getBufferBuilders().getEntityVertexConsumers(), cam);
    }

    static void render(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cam) {
        MegaConfig cfg = MegaClient.config;
        MinecraftClient client = MinecraftClient.getInstance();
        if (cfg == null || !cfg.hudEnabled || (!cfg.tracers && !cfg.boxes && !cfg.tracePlayers && !cfg.outlineSneaky && !cfg.xray && !cfg.waypoints)) return;
        if (client.player == null || client.world == null) return;

        Vector3fc fwd = client.gameRenderer.getCamera().getHorizontalPlane(); // look direction
        // start just in front of the eyes so the line comes from the crosshair
        float sx = fwd.x(), sy = fwd.y(), sz = fwd.z();

        MatrixStack.Entry entry = matrices.peek();
        float width = (float) cfg.lineWidth;
        boolean xrayOn = MegaXray.active();

        // ---- X-ray outlines first, depth-tested: blocks X-ray keeps (ores, stash blocks, bedrock) hide the lines
        // behind them, so you get a clear view instead of lines showing through solid blocks.
        if (xrayOn) {
            VertexConsumer dbuf = consumers.getBuffer(DEPTH_LAYER);
            // X-ray ghost outlines: hidden blocks next to air, in their own colour
            if (MegaXray.active() && (cfg.xrayGhost || cfg.xrayFluids)) {
                long[] gp = MegaXray.ghostPos;
                int[] gc = MegaXray.ghostColor;
                int gn = Math.min(MegaXray.ghostCount, Math.min(gp.length, gc.length));
                float gw = width + 1.0f; // thicker so the shape of caves and hidden blocks is easy to read
                for (int i = 0; i < gn; i++) {
                    long l = gp[i];
                    VertexRendering.drawOutline(matrices, dbuf, CUBE_OUT,
                        net.minecraft.util.math.BlockPos.unpackLongX(l) - cam.x,
                        net.minecraft.util.math.BlockPos.unpackLongY(l) - cam.y,
                        net.minecraft.util.math.BlockPos.unpackLongZ(l) - cam.z, gc[i], gw);
                }
            }

            // X-ray: edges of every matching block, in its category colour
            if (cfg.xray && cfg.xrayEdges) {
                for (Target t : MegaClient.xrayBlocks) {
                    VertexRendering.drawOutline(matrices, dbuf, CUBE_OUT,
                        t.pos().getX() - cam.x, t.pos().getY() - cam.y, t.pos().getZ() - cam.z, t.category().color, width + 1.0f);
                }
            }

            if (cfg.boxes) { // target boxes too, a bit thicker
                List<Target> ts = MegaClient.targets;
                for (int i = 0; i < Math.min(cfg.maxTracers, ts.size()); i++) {
                    Target t = ts.get(i);
                    VertexRendering.drawOutline(matrices, dbuf, CUBE_OUT, t.pos().getX() - cam.x, t.pos().getY() - cam.y,
                        t.pos().getZ() - cam.z, t.category().color, width + 1.0f);
                }
            }
            if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(DEPTH_LAYER);
        }

        // ---- Everything else: through walls
        VertexConsumer buf = consumers.getBuffer(LAYER);

        // Waypoints and deaths: a tall beam you can see from far away, a box on the block, and a line if lines are on
        for (MegaWaypoints.Waypoint w : MegaWaypoints.here(client)) {
            float wx = (float) (w.x + 0.5 - cam.x), wy = (float) (w.y - cam.y), wz = (float) (w.z + 0.5 - cam.z);
            line(buf, entry, wx, wy, wz, wx, wy + 300, wz, w.color, width + 1);
            VertexRendering.drawOutline(matrices, buf, VoxelShapes.fullCube(), w.x - cam.x, w.y - cam.y, w.z - cam.z, w.color, width);
            if (cfg.tracers) line(buf, entry, sx, sy, sz, wx, wy + 0.5f, wz, w.color, width);
        }

        List<Target> targets = MegaClient.targets;
        int n = Math.min(cfg.maxTracers, targets.size());
        for (int i = 0; i < n; i++) {
            Target t = targets.get(i);
            double bx = t.pos().getX() - cam.x, by = t.pos().getY() - cam.y, bz = t.pos().getZ() - cam.z;
            int color = t.category().color;
            if (cfg.tracers) line(buf, entry, sx, sy, sz, (float) (bx + 0.5), (float) (by + 0.5), (float) (bz + 0.5), color, width);
            if (cfg.boxes && !xrayOn) VertexRendering.drawOutline(matrices, buf, VoxelShapes.fullCube(), bx, by, bz, color, width);
        }

        if (cfg.outlineSneaky) {
            float delta = client.getRenderTickCounter().getTickProgress(true);
            double maxSq = (double) cfg.playerRange * cfg.playerRange;
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (MegaClient.skipPlayer(client, p)) continue;
                boolean invisible = p.isInvisible();
                if (!invisible && !p.isInSneakingPose()) continue;
                if (client.player.squaredDistanceTo(p) > maxSq) continue;
                Vec3d pos = p.getLerpedPos(delta);
                VertexRendering.drawOutline(matrices, buf,
                    VoxelShapes.cuboid(p.getBoundingBox().offset(-p.getX(), -p.getY(), -p.getZ())),
                    pos.x - cam.x, pos.y - cam.y, pos.z - cam.z,
                    invisible ? INVISIBLE_COLOR : YELLOW, width + 1);
            }
        }

        if (cfg.tracePlayers) {
            float delta = client.getRenderTickCounter().getTickProgress(true);
            double maxSq = (double) cfg.playerRange * cfg.playerRange;
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (MegaClient.skipPlayer(client, p)) continue;
                if (p.isInvisible() && !cfg.showInvisiblePlayers) continue;
                if (client.player.squaredDistanceTo(p) > maxSq) continue;
                Vec3d pos = p.getLerpedPos(delta);
                float tx = (float) (pos.x - cam.x), ty = (float) (pos.y + p.getHeight() / 2 - cam.y), tz = (float) (pos.z - cam.z);
                stripedLine(buf, entry, sx, sy, sz, tx, ty, tz, width);
            }
        }

        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(LAYER);

        // X-ray: see-through lava. Sides facing hidden rock get a translucent orange fill.
        if (MegaXray.active() && cfg.xrayFluids && MegaXray.lavaCount > 0) {
            RenderLayer fill = RenderLayers.debugFilledBox();
            VertexConsumer fb = consumers.getBuffer(fill);
            Matrix4f mat = entry.getPositionMatrix();
            long[] lp = MegaXray.lavaPos;
            byte[] lm = MegaXray.lavaMask;
            int ln = Math.min(MegaXray.lavaCount, Math.min(lp.length, lm.length));
            for (int i = 0; i < ln; i++) {
                float x = (float) (net.minecraft.util.math.BlockPos.unpackLongX(lp[i]) - cam.x);
                float y = (float) (net.minecraft.util.math.BlockPos.unpackLongY(lp[i]) - cam.y);
                float z = (float) (net.minecraft.util.math.BlockPos.unpackLongZ(lp[i]) - cam.z);
                int mask = lm[i];
                for (int di = 0; di < MegaXray.DIRS.length; di++) {
                    if ((mask & (1 << di)) == 0) continue;
                    lavaFace(fb, mat, x, y, z, MegaXray.DIRS[di]);
                }
            }
            if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(fill);
        }
    }

    private static final int LAVA_FILL = 0x73FF5A00; // ~45% opaque orange

    /** One face of a block as a quad, emitted in both windings so it shows from either side. */
    private static void lavaFace(VertexConsumer b, Matrix4f m, float x, float y, float z, net.minecraft.util.math.Direction d) {
        float x0 = x, y0 = y, z0 = z, x1 = x + 1, y1 = y + 1, z1 = z + 1;
        float[][] q = switch (d) {
            case DOWN -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case UP -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case NORTH -> new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
        };
        for (int k = 0; k < 4; k++) b.vertex(m, q[k][0], q[k][1], q[k][2]).color(LAVA_FILL);
        for (int k = 3; k >= 0; k--) b.vertex(m, q[k][0], q[k][1], q[k][2]).color(LAVA_FILL);
    }

    /** Yellow/black hazard-striped line for players. */
    private static void stripedLine(VertexConsumer buf, MatrixStack.Entry e, float x1, float y1, float z1,
                                    float x2, float y2, float z2, float width) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.min(400, Math.max(1, (int) Math.ceil(len / STRIPE)));
        for (int i = 0; i < n; i++) {
            float a = (float) i / n, b = (float) (i + 1) / n;
            line(buf, e, x1 + dx * a, y1 + dy * a, z1 + dz * a, x1 + dx * b, y1 + dy * b, z1 + dz * b,
                (i & 1) == 0 ? YELLOW : BLACK, width + 1);
        }
    }

    private static void line(VertexConsumer buf, MatrixStack.Entry e, float x1, float y1, float z1,
                             float x2, float y2, float z2, int color, float width) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4f) return;
        dx /= len; dy /= len; dz /= len;
        buf.vertex(e, x1, y1, z1).color(color).normal(e, dx, dy, dz).lineWidth(width);
        buf.vertex(e, x2, y2, z2).color(color).normal(e, dx, dy, dz).lineWidth(width);
    }
}
