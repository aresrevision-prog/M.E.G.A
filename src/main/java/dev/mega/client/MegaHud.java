package dev.mega.client;

import dev.mega.client.MegaClient.Category;
import dev.mega.client.MegaClient.PlayerInfo;
import dev.mega.client.MegaClient.Target;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/** Draws the HUD. All colours are full ARGB — since 1.21.6, text with alpha 0 is not drawn. */
public final class MegaHud {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int YELLOW = 0xFFFFD400;
    private static final int YELLOW_DIM = 0xFFB89600;
    private static final int PANEL = 0x90000000;

    private MegaHud() {}

    public static void render(MinecraftClient client, DrawContext ctx) {
        MegaConfig cfg = MegaClient.config;
        if (cfg == null || !cfg.hudEnabled || client.player == null || client.world == null) return;
        if (client.getDebugHud().shouldShowDebugHud()) return;

        TextRenderer tr = client.textRenderer;
        int x = cfg.hudX;
        int y = cfg.hudY;
        double px = client.player.getX();
        double pz = client.player.getZ();
        float yaw = client.player.getYaw();

        // Farm alert banner, top centre. Everything else in the top corners moves down below it.
        int topPad = 0;
        MegaClient.Target farm = cfg.farmAlert ? MegaClient.nearestFarm() : null;
        if (cfg.farmAlert && (MegaClient.farmCount > 0 || farm != null)) {
            StringBuilder m = new StringBuilder("FARM DETECTED");
            if (farm != null) {
                double fx = farm.pos().getX() + 0.5, fz = farm.pos().getZ() + 0.5;
                double d = Math.sqrt(sq(fx - px) + sq(farm.pos().getY() + 0.5 - client.player.getY()) + sq(fz - pz));
                m.append("  ").append(farm.label().replace('_', ' ')).append(' ').append(Math.round(d)).append("m ")
                    .append(arrow(px, pz, yaw, fx, fz));
            }
            if (MegaClient.farmCount > 0) {
                double fd = Math.sqrt(sq(MegaClient.farmX - px) + sq(MegaClient.farmY - client.player.getY()) + sq(MegaClient.farmZ - pz));
                m.append("  ").append(MegaClient.farmCount).append(" moving items ").append(Math.round(fd)).append("m ")
                    .append(arrow(px, pz, yaw, MegaClient.farmX, MegaClient.farmZ));
            }
            String msg = m.toString();
            int sw0 = ctx.getScaledWindowWidth();
            if (tr.getWidth(msg) + 16 > sw0 - 8) msg = msg.replace("FARM DETECTED  ", "FARM  ").replace(" moving items ", " items ");
            int w = tr.getWidth(msg) + 16;
            int bx = Math.max(2, sw0 / 2 - w / 2);
            int by = 4;
            ctx.fill(bx, by, bx + w, by + 18, 0xDD000000);
            ctx.fill(bx, by, bx + w, by + 2, 0xFFFF8800);
            ctx.fill(bx, by + 16, bx + w, by + 18, 0xFFFF8800);
            ctx.drawTextWithShadow(tr, msg, bx + 8, by + 5, 0xFFFF8800);
            topPad = 24;
            y += topPad;
        }

        // "Blocks seen" panel, top-right corner
        if (cfg.blockCountPanel) {
            List<MegaClient.BlockCount> bc = MegaClient.blockCounts;
            int sw = ctx.getScaledWindowWidth();
            String title = "Blocks seen";
            int total = 0;
            for (MegaClient.BlockCount b : bc) total += b.count();
            title += " (" + total + ")";
            int w = tr.getWidth(title);
            int n = Math.min(bc.size(), cfg.blockCountLines);
            for (int i = 0; i < n; i++) w = Math.max(w, tr.getWidth("■ " + bc.get(i).label() + "  " + bc.get(i).count()));
            int rx = sw - w - 8, ry = 6 + topPad;
            ctx.fill(rx - 4, ry - 2, sw - 4, ry + 11 + Math.max(n, 1) * 10 + 2, PANEL);
            ctx.drawTextWithShadow(tr, title, rx, ry, WHITE);
            ry += 11;
            if (n == 0) ctx.drawTextWithShadow(tr, "nothing yet", rx, ry, GRAY);
            for (int i = 0; i < n; i++) {
                MegaClient.BlockCount b = bc.get(i);
                String cnt = String.valueOf(b.count());
                ctx.drawTextWithShadow(tr, "■", rx, ry, b.color());
                ctx.drawTextWithShadow(tr, b.label(), rx + tr.getWidth("■ "), ry, WHITE);
                ctx.drawTextWithShadow(tr, cnt, sw - 8 - tr.getWidth(cnt), ry, b.color());
                ry += 10;
            }
        }

        // Player warning banner
        if (cfg.playerWarning && MegaClient.playerVisible) {
            String warn = "WARNING: PLAYER VISIBLE";
            int w = tr.getWidth(warn) + 14;
            ctx.fill(x, y, x + w, y + 18, 0xDD000000);
            ctx.fill(x, y, x + 3, y + 18, YELLOW);
            ctx.drawTextWithShadow(tr, warn, x + 8, y + 5, YELLOW);
            y += 22;
        }

        // Nearby players
        List<PlayerInfo> players = MegaClient.players;
        if (cfg.playerList && !players.isEmpty()) {
            y = header(ctx, tr, "Players (" + players.size() + ")", x, y);
            int shown = 0;
            for (PlayerInfo p : players) {
                if (shown++ >= Math.min(5, cfg.maxLines)) break;
                String line = arrow(px, pz, yaw, p.x(), p.z()) + " " + p.name() + "  " + Math.round(p.distance()) + "m";
                y = line(ctx, tr, line, x, y, p.visible() ? YELLOW : YELLOW_DIM);
            }
            y += 4;
        }

        // Block targets
        List<Target> targets = MegaClient.targets;
        StringBuilder head = new StringBuilder("M.E.G.A v").append(MegaClient.version);
        if (cfg.xray) head.append("  X-RAY");
        for (Category c : Category.values()) {
            Integer n = MegaClient.counts.get(c);
            if (n != null) head.append("  ").append(c.code).append(':').append(n);
        }
        y = header(ctx, tr, head.toString(), x, y);

        if (targets.isEmpty()) {
            line(ctx, tr, "No targets within " + cfg.maxDistance + "m", x, y, GRAY);
            return;
        }
        int shown = 0;
        for (Target t : targets) {
            if (shown++ >= cfg.maxLines) break;
            double tx = t.pos().getX() + 0.5;
            double tz = t.pos().getZ() + 0.5;
            double dist = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz)
                + sq(t.pos().getY() + 0.5 - client.player.getY()));
            String line = String.format("%s %s  %dm  %d, %d, %d", arrow(px, pz, yaw, tx, tz),
                t.label(), Math.round(dist), t.pos().getX(), t.pos().getY(), t.pos().getZ());
            y = line(ctx, tr, line, x, y, t.category().color);
        }
    }

    private static int header(DrawContext ctx, TextRenderer tr, String text, int x, int y) {
        ctx.fill(x - 2, y - 2, x + tr.getWidth(text) + 2, y + 10, PANEL);
        ctx.drawTextWithShadow(tr, text, x, y, WHITE);
        return y + 12;
    }

    private static int line(DrawContext ctx, TextRenderer tr, String text, int x, int y, int color) {
        ctx.fill(x - 2, y - 1, x + tr.getWidth(text) + 2, y + 10, PANEL);
        ctx.drawTextWithShadow(tr, text, x, y, color);
        return y + 11;
    }

    /** Arrow pointing to the target relative to where the player is facing. */
    private static String arrow(double px, double pz, float yaw, double tx, double tz) {
        double targetYaw = Math.toDegrees(Math.atan2(tz - pz, tx - px)) - 90.0;
        double rel = MathHelper.wrapDegrees(targetYaw - yaw);
        return ARROWS[Math.floorMod((int) Math.round(rel / 45.0), 8)];
    }

    private static double sq(double v) { return v * v; }
}
