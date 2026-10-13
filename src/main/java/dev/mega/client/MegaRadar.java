package dev.mega.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.world.Heightmap;

/**
 * Three radars, bottom right: SKY (10+ blocks above the ground under it), GROUND, and BELOW 0 (y under 0).
 * Rotates with you (up = where you're facing). Distances are square-root scaled, so nearby things spread out
 * while far ones still fit. Only shows entities the server has sent your game.
 */
public final class MegaRadar {
    static final String[] NAMES = {"SKY", "GROUND", "BELOW 0"};
    static final int PLAYER = 0xFFFFD400, HOSTILE = 0xFFFF4444, ANIMAL = 0xFF55DD55, VEHICLE = 0xFFAAAAAA;
    private static final int MAX = 400;

    /** Per layer: dot x/z offsets (already rotated, -1..1) and colours. Rebuilt every 5 ticks. */
    static final float[][] dx = new float[3][MAX], dz = new float[3][MAX];
    static final int[][] col = new int[3][MAX];
    static final int[] count = new int[3];
    private static int timer;

    private MegaRadar() {}

    static void tick(MinecraftClient client) {
        MegaConfig cfg = MegaClient.config;
        if (!cfg.radar || client.world == null || client.player == null) { count[0] = count[1] = count[2] = 0; return; }
        if (++timer < 5) return;
        timer = 0;
        int[] n = new int[3];
        double range = cfg.radarRange, px = client.player.getX(), pz = client.player.getZ();
        double yaw = Math.toRadians(client.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        for (Entity e : client.world.getEntities()) {
            if (e == client.player) continue;
            int c;
            if (e instanceof AbstractClientPlayerEntity p) {
                if (MegaClient.skipPlayer(client, p) || (p.isInvisible() && !cfg.showInvisiblePlayers)) continue;
                c = PLAYER;
            } else if (e instanceof Monster) c = HOSTILE;
            else if (e instanceof PassiveEntity || e instanceof WaterCreatureEntity) c = ANIMAL;
            else if (e instanceof VehicleEntity) c = VEHICLE;
            else continue; // items, arrows, xp...
            double ox = e.getX() - px, oz = e.getZ() - pz;
            double d = Math.sqrt(ox * ox + oz * oz);
            if (d > range) continue;
            int layer = layer(client, e);
            if (n[layer] >= MAX) continue;
            double s = d < 1e-6 ? 0 : Math.sqrt(d / range) / d; // square-root scale
            dx[layer][n[layer]] = (float) ((ox * rx + oz * rz) * s);
            dz[layer][n[layer]] = (float) (-(ox * fx + oz * fz) * s);
            col[layer][n[layer]] = c;
            n[layer]++;
        }
        System.arraycopy(n, 0, count, 0, 3);
    }

    /** 0 = sky, 1 = ground, 2 = below 0. */
    static int layer(MinecraftClient client, Entity e) {
        int ground = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, e.getBlockX(), e.getBlockZ());
        if (e.getY() > ground + 10) return 0;
        return e.getY() < 0 ? 2 : 1;
    }

    static void render(MinecraftClient client, DrawContext ctx) {
        MegaConfig cfg = MegaClient.config;
        if (!cfg.radar || client.player == null) return;
        TextRenderer tr = client.textRenderer;
        int size = cfg.radarSize, gap = 4;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int y = sh - size - 6;
        int x0 = sw - 3 * (size + gap) - 2;
        for (int l = 0; l < 3; l++) {
            int x = x0 + l * (size + gap);
            ctx.fill(x, y, x + size, y + size, 0x90000000);
            border(ctx, x, y, size, l == 0 ? 0xFF7FB2FF : l == 1 ? 0xFF6FBF4F : 0xFF8A6A4A);
            int cx = x + size / 2, cy = y + size / 2;
            ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF); // you
            ctx.fill(cx, y + 2, cx + 1, cy - 2, 0x40FFFFFF);       // facing line
            int half = size / 2 - 3;
            for (int i = 0; i < count[l]; i++) {
                int ex = cx + Math.round(dx[l][i] * half), ey = cy + Math.round(dz[l][i] * half);
                int c = col[l][i];
                if (c == PLAYER) {
                    ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, 0xFF000000);
                    ctx.fill(ex - 1, ey - 1, ex + 2, ey + 2, c);
                } else ctx.fill(ex - 1, ey - 1, ex + 1, ey + 1, c);
            }
            String label = NAMES[l] + " " + count[l];
            ctx.drawTextWithShadow(tr, label, x + 2, y - 10, 0xFFFFFFFF);
        }
        ctx.drawTextWithShadow(tr, cfg.radarRange + "m", x0 + 3 * (size + gap) - gap - tr.getWidth(cfg.radarRange + "m") - 2,
            y + size - 9, 0xFF9A9A9A);
    }

    private static void border(DrawContext ctx, int x, int y, int s, int c) {
        ctx.fill(x, y, x + s, y + 1, c);
        ctx.fill(x, y + s - 1, x + s, y + s, c);
        ctx.fill(x, y, x + 1, y + s, c);
        ctx.fill(x + s - 1, y, x + s, y + s, c);
    }
}
