package dev.mega.client;

import dev.mega.client.MegaClient.Category;
import dev.mega.client.MegaClient.PlayerInfo;
import dev.mega.client.MegaClient.Target;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

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
        boolean radarsInCorner = cfg.cornerSlot1.equals("radars") || cfg.cornerSlot2.equals("radars");
        if (!radarsInCorner) MegaRadar.render(client, ctx); // bottom right

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

        // Elytra helper: speed / height / compass bar while gliding
        if (cfg.elytraHelper && client.player.isGliding()) topPad = elytraBar(client, ctx, tr, topPad);

        // Top-right corner: two slots, each Minimap / Blocks seen / Radars (panel → Map)
        int sw = ctx.getScaledWindowWidth();
        int cy = 6 + topPad;
        for (String slot : new String[]{cfg.cornerSlot1, cfg.cornerSlot2}) {
            switch (slot) {
                case "minimap" -> {
                    int size = Math.min(cfg.minimapSize, ctx.getScaledWindowHeight() / 3);
                    cy = MegaMap.render(client, ctx, sw - size - 5, cy, size) + 6;
                }
                case "blocks" -> { if (cfg.blockCountPanel) cy = blocksSeen(client, ctx, tr, cfg, cy, topPad) + 6; }
                case "radars" -> {
                    int size = Math.max(28, Math.min(cfg.radarSize, (sw / 3 - 16) / 3));
                    MegaRadar.renderAt(client, ctx, sw - 3 * (size + 4) - 2, cy + 10, size);
                    cy += size + 16;
                }
                default -> { }
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
                String line = arrow(px, pz, yaw, p.x(), p.z()) + " " + p.name() + "  " + Math.round(p.distance()) + "m"
                    + (p.gear().isEmpty() ? "" : "  " + p.gear());
                y = line(ctx, tr, line, x, y, p.visible() ? YELLOW : YELLOW_DIM);
            }
            y += 4;
        }

        // Block targets
        List<Target> targets = MegaClient.targets;
        StringBuilder head = new StringBuilder("M.E.G.A v").append(MegaClient.version);
        if (cfg.xray) head.append("  X-RAY");
        if (cfg.deepScan) head.append("  DEEP");
        for (Category c : Category.values()) {
            Integer n = MegaClient.counts.get(c);
            if (n != null) head.append("  ").append(c.code).append(':').append(n);
        }
        y = header(ctx, tr, head.toString(), x, y);

        if (targets.isEmpty()) y = line(ctx, tr, "No targets within " + cfg.maxDistance + "m", x, y, GRAY);
        int shown = 0;
        for (Target t : targets) {
            if (shown++ >= cfg.maxLines) break;
            double tx = t.pos().getX() + 0.5;
            double tz = t.pos().getZ() + 0.5;
            double dist = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz)
                + sq(t.pos().getY() + 0.5 - client.player.getY()));
            String line = String.format("%s %s  %dm  %d, %d, %d", arrow(px, pz, yaw, tx, tz),
                t.label(), Math.round(dist), t.pos().getX(), t.pos().getY(), t.pos().getZ());
            y = line(ctx, tr, line, x, y, MegaClient.readable(t.category().color));
        }

        // Players you saw recently who are out of sight now
        if (cfg.lastSeen) {
            List<MegaTracking.Seen> gone = MegaTracking.recentlyGone(client, 3);
            if (!gone.isEmpty()) {
                y = header(ctx, tr, "Last seen", x, y + 4);
                for (MegaTracking.Seen g : gone) {
                    long secs = (System.currentTimeMillis() - g.t()) / 1000;
                    double d = Math.sqrt(sq(g.x() - px) + sq(g.y() - client.player.getY()) + sq(g.z() - pz));
                    String line = String.format("%s %s  %s ago  %dm", arrow(px, pz, yaw, g.x(), g.z()), g.name(),
                        secs < 60 ? secs + "s" : secs / 60 + "m", Math.round(d));
                    y = line(ctx, tr, line, x, y, 0xFFB89600);
                }
            }
        }

        // Waypoints and deaths in this dimension, nearest first
        List<MegaWaypoints.Waypoint> wps = new java.util.ArrayList<>(MegaWaypoints.here(client));
        if (!wps.isEmpty()) {
            double py = client.player.getY();
            wps.sort(java.util.Comparator.comparingDouble(w -> w.distanceTo(px, py, pz)));
            y = header(ctx, tr, "Waypoints", x, y + 4);
            for (int i = 0; i < Math.min(5, wps.size()); i++) {
                MegaWaypoints.Waypoint w = wps.get(i);
                String line = String.format("%s %s%s  %dm  %d, %d, %d", arrow(px, pz, yaw, w.x + 0.5, w.z + 0.5),
                    w.death ? "☠ " : "", w.name, Math.round(w.distanceTo(px, py, pz)), w.x, w.y, w.z);
                y = line(ctx, tr, line, x, y, MegaClient.readable(w.color));
            }
        }
    }

    /** Compass strip (±90° of where you face) with N/E/S/W, nearest stashes and waypoints; speed and height. */
    private static int elytraBar(MinecraftClient client, DrawContext ctx, TextRenderer tr, int topPad) {
        int sw = ctx.getScaledWindowWidth(), w = Math.min(220, sw - 260 > 120 ? sw - 260 : 120);
        int x = sw / 2 - w / 2, y = 4 + topPad;
        double heading = MathHelper.wrapDegrees(client.player.getYaw() + 180) ; // 0 = north, 90 = east
        ctx.fill(x, y, x + w, y + 22, 0xB0000000);
        ctx.fill(x + w / 2, y, x + w / 2 + 1, y + 12, 0xFFFFFFFF);
        String[] card = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int a = 0; a < 360; a += 15) {
            double rel = MathHelper.wrapDegrees(a - heading);
            if (Math.abs(rel) > 90) continue;
            int sx = x + w / 2 + (int) (rel / 90 * (w / 2 - 6));
            if (a % 45 == 0) ctx.drawCenteredTextWithShadow(tr, card[a / 45], sx, y + 2, a % 90 == 0 ? 0xFFFFD400 : 0xFFCCCCCC);
            else ctx.fill(sx, y + 4, sx + 1, y + 8, 0x80FFFFFF);
        }
        // markers: nearest stash of each colour + waypoints
        double px = client.player.getX(), pz = client.player.getZ();
        java.util.List<Object[]> marks = new java.util.ArrayList<>();
        java.util.Set<MegaClient.Category> seen = java.util.EnumSet.noneOf(MegaClient.Category.class);
        for (Target t : MegaClient.targets) if (!MegaClient.natural(t.label(), t.category().color) && seen.add(t.category()))
            marks.add(new Object[]{t.pos().getX() + 0.5, t.pos().getZ() + 0.5, MegaClient.readable(t.category().color)});
        for (MegaWaypoints.Waypoint wp : MegaWaypoints.here(client)) marks.add(new Object[]{wp.x + 0.5, wp.z + 0.5, wp.color});
        for (Object[] m : marks) {
            double bearing = Math.toDegrees(Math.atan2((double) m[0] - px, -((double) m[1] - pz)));
            double rel = MathHelper.wrapDegrees(bearing - heading);
            int sx = x + w / 2 + (int) (Math.max(-90, Math.min(90, rel)) / 90 * (w / 2 - 6));
            ctx.fill(sx - 1, y + 10, sx + 2, y + 13, (int) m[2]);
        }
        Vec3d v = client.player.getVelocity();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z) * 20;
        String info = String.format("%.0f blocks/s   height %d   %s", speed, client.player.getBlockY(), card[(int) Math.round(((heading % 360) + 360) % 360 / 45) % 8]);
        ctx.drawCenteredTextWithShadow(tr, info, x + w / 2, y + 13, 0xFFFFFFFF);
        return topPad + 26;
    }

    /** "Blocks seen" list at the top-right, starting at y; returns the y below it. */
    private static int blocksSeen(MinecraftClient client, DrawContext ctx, TextRenderer tr, MegaConfig cfg, int startY, int topPad) {
            List<MegaClient.BlockCount> bc = MegaClient.blockCounts;
            int sw = ctx.getScaledWindowWidth();
            String title = "Blocks seen";
            int total = 0;
            for (MegaClient.BlockCount b : bc) total += b.count();
            title += " (" + total + ")";
            int w = tr.getWidth(title);
            int n = Math.min(bc.size(), cfg.blockCountLines);
            // never run down into the radars
            int limit = (MegaRadar.top(client, ctx) - 14 - startY - 11) / 10;
            n = Math.max(0, Math.min(n, limit));
            for (int i = 0; i < n; i++) w = Math.max(w, tr.getWidth("■ " + bc.get(i).label() + "  " + bc.get(i).count()));
            int rx = sw - w - 8, ry = startY;
            ctx.fill(rx - 4, ry - 2, sw - 4, ry + 11 + Math.max(n, 1) * 10 + 2, PANEL);
            ctx.drawTextWithShadow(tr, title, rx, ry, WHITE);
            ry += 11;
            if (n == 0) { ctx.drawTextWithShadow(tr, "nothing yet", rx, ry, GRAY); ry += 10; }
            for (int i = 0; i < n; i++) {
                MegaClient.BlockCount b = bc.get(i);
                String cnt = String.valueOf(b.count());
                ctx.drawTextWithShadow(tr, "■", rx, ry, b.color());
                ctx.drawTextWithShadow(tr, b.label(), rx + tr.getWidth("■ "), ry, WHITE);
                ctx.drawTextWithShadow(tr, cnt, sw - 8 - tr.getWidth(cnt), ry, MegaClient.readable(b.color()));
                ry += 10;
            }
            return ry + 2;
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
