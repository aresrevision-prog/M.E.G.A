package dev.mega.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;

/**
 * Big map (M): everything you've ever had loaded on this server/dimension, plus remembered stashes, waypoints,
 * deaths, players and where you last saw people. Drag to move, scroll to zoom.
 */
final class MegaMapScreen extends Screen {
    private static final Identifier ID = Identifier.of(MegaClient.MOD_ID, "bigmap");
    private static double zoom = 1.0;
    private double cx, cz;
    private NativeImageBackedTexture tex;
    private int texW, texH, refresh;
    private boolean dirty = true;
    private int mouseX, mouseY;

    MegaMapScreen() {
        super(Text.literal("M.E.G.A map"));
    }

    @Override
    protected void init() {
        if (client.player != null && cx == 0 && cz == 0) { cx = client.player.getX(); cz = client.player.getZ(); }
        addDrawableChild(ButtonWidget.builder(Text.literal("Centre on me"), b -> {
            cx = client.player.getX(); cz = client.player.getZ(); dirty = true;
        }).dimensions(4, height - 24, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(width - 64, height - 24, 60, 20).build());
        dirty = true;
    }

    private void rebuild() {
        if (tex == null || texW != width || texH != height) {
            texW = width; texH = height;
            tex = new NativeImageBackedTexture(() -> "mega big map", texW, texH, true);
            client.getTextureManager().registerTexture(ID, tex);
        }
        Map<Long, byte[]> mem = MegaMap.chunks(client);
        long lastKey = Long.MIN_VALUE;
        byte[] last = null;
        for (int sy = 0; sy < texH; sy++) {
            int wz = (int) Math.floor(cz + (sy - texH / 2.0) / zoom);
            for (int sx = 0; sx < texW; sx++) {
                int wx = (int) Math.floor(cx + (sx - texW / 2.0) / zoom);
                long key = ChunkPos.toLong(wx >> 4, wz >> 4);
                if (key != lastKey) { last = mem.get(key); lastKey = key; }
                int c = last == null ? 0 : MegaMap.colour(last[(wz & 15) * 16 + (wx & 15)]);
                tex.getImage().setColorArgb(sx, sy, c == 0 ? 0xFF101014 : c);
            }
        }
        tex.upload();
        dirty = false;
    }

    @Override
    public void tick() {
        if (++refresh >= 40) { refresh = 0; dirty = true; } // new areas appear while it's open
    }

    /** The map itself is the background (no blur). */
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        if (dirty) rebuild();
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ID, 0, 0, 0f, 0f, texW, texH, texW, texH);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        super.render(ctx, mx, my, delta); // map + buttons
        String hover = null;
        double best = 36;

        // remembered stashes
        for (MegaTracking.Mem m : MegaTracking.stashes(client).values()) {
            int[] s = screen(m.x + 0.5, m.z + 0.5);
            if (!on(s)) continue;
            ctx.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, 0xFF000000);
            ctx.fill(s[0] - 1, s[1] - 1, s[0] + 2, s[1] + 2, MegaClient.readable(m.color));
            double d = sq(s[0] - mx) + sq(s[1] - my);
            if (d < best) { best = d; hover = m.label + "  " + m.x + ", " + m.y + ", " + m.z; }
        }
        // stashes found right now
        for (MegaClient.Target t : MegaClient.targets) {
            int[] s = screen(t.pos().getX() + 0.5, t.pos().getZ() + 0.5);
            if (!on(s)) continue;
            ctx.fill(s[0] - 1, s[1] - 1, s[0] + 2, s[1] + 2, MegaClient.readable(t.category().color));
            double d = sq(s[0] - mx) + sq(s[1] - my);
            if (d < best) { best = d; hover = t.label() + "  " + t.pos().getX() + ", " + t.pos().getY() + ", " + t.pos().getZ(); }
        }
        // waypoints and deaths
        for (MegaWaypoints.Waypoint w : MegaWaypoints.here(client)) {
            int[] s = screen(w.x + 0.5, w.z + 0.5);
            if (!on(s)) continue;
            ctx.fill(s[0] - 3, s[1] - 3, s[0] + 4, s[1] + 4, 0xFF000000);
            ctx.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, w.color);
            double d = sq(s[0] - mx) + sq(s[1] - my);
            if (d < best) { best = d; hover = (w.death ? "☠ " : "") + w.name + "  " + w.x + ", " + w.y + ", " + w.z; }
        }
        // where you last saw people
        for (MegaTracking.Seen seen : MegaTracking.recentlyGone(client, 20)) {
            int[] s = screen(seen.x(), seen.z());
            if (!on(s)) continue;
            ctx.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, 0xFF7A6A00);
            double d = sq(s[0] - mx) + sq(s[1] - my);
            if (d < best) { best = d; hover = seen.name() + " last seen " + ago(seen.t()); }
        }
        // players now
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p != client.player && MegaClient.skipPlayer(client, p)) continue;
            int[] s = screen(p.getX(), p.getZ());
            if (!on(s)) continue;
            int col = p == client.player ? 0xFFFFFFFF : 0xFFFFD400;
            ctx.fill(s[0] - 3, s[1] - 3, s[0] + 4, s[1] + 4, 0xFF000000);
            ctx.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, col);
            double fa = Math.toRadians(p.getYaw());
            int tx = s[0] + (int) Math.round(-Math.sin(fa) * 6), ty = s[1] + (int) Math.round(Math.cos(fa) * 6);
            ctx.fill(tx - 1, ty - 1, tx + 2, ty + 2, col);
            double d = sq(s[0] - mx) + sq(s[1] - my);
            if (d < best && p != client.player) { best = d; hover = p.getName().getString(); }
        }

        int mxW = (int) Math.floor(cx + (mx - texW / 2.0) / zoom), mzW = (int) Math.floor(cz + (my - texH / 2.0) / zoom);
        String top = "M.E.G.A map  ·  drag to move, scroll to zoom  ·  " + MegaMap.chunks(client).size() + " chunks explored  ·  "
            + MegaTracking.stashes(client).size() + " stashes remembered";
        ctx.fill(0, 0, width, 12, 0xA0000000);
        ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(top, width - 4), width / 2, 2, 0xFFFFD400);
        String cur = mxW + ", " + mzW + "   zoom " + (zoom >= 1 ? (int) zoom + "x" : "1/" + Math.round(1 / zoom) + "x");
        ctx.drawTextWithShadow(textRenderer, cur, 100, height - 18, 0xFFDDDDDD);
        if (hover != null) {
            int w = textRenderer.getWidth(hover) + 6;
            int hx = Math.min(mx + 8, width - w - 2), hy = Math.max(14, my - 14);
            ctx.fill(hx, hy, hx + w, hy + 12, 0xE0000000);
            ctx.drawTextWithShadow(textRenderer, hover, hx + 3, hy + 2, 0xFFFFFFFF);
        }
    }

    private int[] screen(double wx, double wz) {
        return new int[]{(int) Math.round(texW / 2.0 + (wx - cx) * zoom), (int) Math.round(texH / 2.0 + (wz - cz) * zoom)};
    }

    private boolean on(int[] s) { return s[0] >= 0 && s[0] < texW && s[1] >= 12 && s[1] < texH; }

    private static String ago(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        return s < 60 ? s + "s ago" : s / 60 + "m ago";
    }

    private static double sq(double v) { return v * v; }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        cx -= dx / zoom;
        cz -= dy / zoom;
        dirty = true;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        double wx = cx + (mx - texW / 2.0) / zoom, wz = cz + (my - texH / 2.0) / zoom; // zoom round the mouse
        zoom = Math.clamp(v > 0 ? zoom * 1.5 : zoom / 1.5, 1 / 16.0, 8.0);
        cx = wx - (mx - texW / 2.0) / zoom;
        cz = wz - (my - texH / 2.0) / zoom;
        dirty = true;
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
