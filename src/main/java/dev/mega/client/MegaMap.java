package dev.mega.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Map memory + the corner minimap.
 * Memory: one byte per block column (map colour + shading) for every chunk you've had loaded, kept per server and
 * dimension in config/mega_map/, so the big map (M) keeps growing as you fly around.
 * Minimap layers: SURFACE (from memory), CAVE (the level you're on: floors of open spaces, rock is dark) and
 * BELOW 0 (the first open space under y=0 in each column: tunnels and hidden rooms).
 */
public final class MegaMap {
    static final String[] LAYERS = {"SURFACE", "CAVE", "BELOW 0"};
    static final int TEX = 128;
    private static final Identifier MINI_ID = Identifier.of(MegaClient.MOD_ID, "minimap");
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("mega_map");
    private static final int[] SHADE = {180, 220, 255, 135};

    private static final Map<String, Map<Long, byte[]>> memory = new HashMap<>();
    private static final Map<Long, Long> sampledAt = new HashMap<>();
    private static final Set<String> dirty = new HashSet<>();
    private static String sampledKey = "";
    private static NativeImageBackedTexture tex;
    private static int timer, sampleTimer;

    private MegaMap() {}

    static String key(MinecraftClient c) { return MegaWaypoints.serverKey(c) + "|" + MegaWaypoints.dim(c); }

    /** Remembered chunks for this server + dimension. */
    static Map<Long, byte[]> chunks(MinecraftClient c) {
        String k = key(c);
        Map<Long, byte[]> m = memory.get(k);
        if (m == null) { m = load(k); memory.put(k, m); }
        return m;
    }

    static int colour(byte b) {
        int id = (b & 0xFF) >> 2;
        if (id == 0) return 0;
        int rgb = MapColor.get(id).color, f = SHADE[b & 3];
        int r = ((rgb >> 16) & 255) * f / 255, g = ((rgb >> 8) & 255) * f / 255, bl = (rgb & 255) * f / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static byte pack(MapColor mc, int shade) { return (byte) ((mc.id << 2) | shade); }

    /** Surface colours of one chunk (top block, shaded by height like a vanilla map). */
    private static byte[] sample(MinecraftClient client, WorldChunk ch) {
        byte[] out = new byte[256];
        int[] h = new int[256];
        int bx = ch.getPos().getStartX(), bz = ch.getPos().getStartZ(), bottom = client.world.getBottomY();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            // start one above the heightmap value and step down past air, so it works whether the value is the
            // top block or the space above it
            int y = ch.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z) + 1;
            MapColor mc = MapColor.CLEAR;
            while (y > bottom) {
                BlockState st = ch.getBlockState(m.set(bx + x, y, bz + z));
                mc = st.getMapColor(client.world, m);
                if (mc != MapColor.CLEAR) break;
                y--;
            }
            h[z * 16 + x] = y;
            int north = z > 0 ? h[(z - 1) * 16 + x] : y;
            int shade = mc == MapColor.WATER_BLUE ? 1 : y > north ? 2 : y < north ? 0 : 1;
            out[z * 16 + x] = pack(mc, shade);
        }
        return out;
    }

    static void tick(MinecraftClient client) {
        MegaConfig cfg = MegaClient.config;
        if (client.world == null || client.player == null) return;
        String k = key(client);
        if (!k.equals(sampledKey)) { sampledAt.clear(); sampledKey = k; }
        // 1. remember the terrain of loaded chunks (nearest first; near ones refresh every 3 s, far ones every 30 s)
        if (++sampleTimer >= 5) {
            sampleTimer = 0;
            Map<Long, byte[]> m = chunks(client);
            ChunkPos c = client.player.getChunkPos();
            int r = client.options.getClampedViewDistance(), budget = 40;
            long now = System.currentTimeMillis();
            for (int ring = 0; ring <= r && budget > 0; ring++)
                for (int dx = -ring; dx <= ring && budget > 0; dx++)
                    for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        long key = ChunkPos.toLong(c.x + dx, c.z + dz);
                        Long at = sampledAt.get(key);
                        if (at != null && now - at < (ring <= 3 ? 3000 : 30000)) continue;
                        WorldChunk ch = client.world.getChunkManager().getWorldChunk(c.x + dx, c.z + dz);
                        if (ch == null) continue;
                        m.put(key, sample(client, ch));
                        sampledAt.put(key, now);
                        dirty.add(k);
                        budget--;
                    }
        }
        // 2. rebuild the minimap picture
        if (!cfg.cornerSlot1.equals("minimap") && !cfg.cornerSlot2.equals("minimap")) return;
        if (++timer < (cfg.minimapLayer == 2 ? 20 : 10)) return;
        timer = 0;
        rebuild(client);
    }

    private static void ensureTexture(MinecraftClient client) {
        if (tex != null) return;
        tex = new NativeImageBackedTexture(() -> "mega minimap", TEX, TEX, true);
        client.getTextureManager().registerTexture(MINI_ID, tex);
    }

    private static void rebuild(MinecraftClient client) {
        ensureTexture(client);
        int layer = MegaClient.config.minimapLayer;
        int px = client.player.getBlockX(), pz = client.player.getBlockZ(), py = client.player.getBlockY();
        int x0 = px - TEX / 2, z0 = pz - TEX / 2;
        Map<Long, byte[]> mem = chunks(client);
        BlockPos.Mutable m = new BlockPos.Mutable();
        int bottom = client.world.getBottomY();
        int step = layer == 2 ? 2 : 1;
        for (int v = 0; v < TEX; v += step) for (int u = 0; u < TEX; u += step) {
            int wx = x0 + u, wz = z0 + v, argb;
            if (layer == 0) {
                byte[] ch = mem.get(ChunkPos.toLong(wx >> 4, wz >> 4));
                argb = ch == null ? 0xFF0A0A0A : colour(ch[(wz & 15) * 16 + (wx & 15)]);
                if (argb == 0) argb = 0xFF0A0A0A;
            } else {
                WorldChunk ch = client.world.getChunkManager().getWorldChunk(wx >> 4, wz >> 4);
                argb = ch == null ? 0xFF0A0A0A : layer == 1 ? cave(client, ch, m, wx, py, wz, bottom) : below0(client, ch, m, wx, wz, bottom);
            }
            for (int dv = 0; dv < step; dv++) for (int du = 0; du < step; du++)
                tex.getImage().setColorArgb(u + du, v + dv, argb);
        }
        tex.upload();
    }

    /** CAVE: at your height, open space shows its floor (darker the deeper); solid rock is near-black. */
    private static int cave(MinecraftClient client, WorldChunk ch, BlockPos.Mutable m, int x, int y, int z, int bottom) {
        BlockState feet = ch.getBlockState(m.set(x, y, z));
        if (!open(feet)) return 0xFF1C1C1C;
        for (int d = 1; d <= 16 && y - d > bottom; d++) {
            BlockState st = ch.getBlockState(m.set(x, y - d, z));
            if (open(st)) continue;
            return shaded(st.getMapColor(client.world, m), d, fluidColour(st.getFluidState()));
        }
        return 0xFF000000;
    }

    /** BELOW 0: the first open space under y=0 (its floor), else black. */
    private static int below0(MinecraftClient client, WorldChunk ch, BlockPos.Mutable m, int x, int z, int bottom) {
        boolean air = false;
        for (int y = Math.min(-1, client.player.getBlockY()); y > bottom; y--) {
            BlockState st = ch.getBlockState(m.set(x, y, z));
            if (open(st)) { air = true; continue; }
            if (air) return shaded(st.getMapColor(client.world, m), Math.min(8, -y / 8), fluidColour(st.getFluidState()));
        }
        return 0xFF050505;
    }

    private static boolean open(BlockState st) {
        return st.isAir() || !st.isOpaque() && st.getFluidState().isEmpty() && st.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
    }

    private static int fluidColour(FluidState f) {
        if (f.isEmpty()) return 0;
        return f.isIn(FluidTags.LAVA) ? 0xFFFF6A00 : 0xFF3F76E4;
    }

    private static int shaded(MapColor mc, int depth, int fluid) {
        int rgb = fluid != 0 ? fluid & 0xFFFFFF : mc == MapColor.CLEAR ? 0x707070 : mc.color;
        int f = Math.max(110, 255 - depth * 9);
        return 0xFF000000 | (((rgb >> 16) & 255) * f / 255 << 16) | (((rgb >> 8) & 255) * f / 255 << 8) | ((rgb & 255) * f / 255);
    }

    // ---------------- drawing ----------------

    /** Draws the minimap at (x, y), size x size. Returns the y just below it. */
    static int render(MinecraftClient client, DrawContext ctx, int x, int y, int size) {
        if (tex == null) { ensureTexture(client); rebuild(client); }
        MegaConfig cfg = MegaClient.config;
        TextRenderer tr = client.textRenderer;
        float yaw = (float) Math.toRadians(client.player.getYaw());
        float a = (float) Math.PI - yaw; // rotate so the way you face is up
        float k = size * 1.41421f / TEX; // pixels per block (texture fills the box when turned)
        int cx = x + size / 2, cy = y + size / 2;
        ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000);
        ctx.enableScissor(x, y, x + size, y + size);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx, cy);
        ctx.getMatrices().rotate(a);
        ctx.getMatrices().scale(k, k);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, MINI_ID, -TEX / 2, -TEX / 2, 0f, 0f, TEX, TEX, TEX, TEX);
        ctx.getMatrices().popMatrix();

        double px = client.player.getX(), pz = client.player.getZ();
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        // mobs
        for (Entity e : client.world.getEntities()) {
            if (e == client.player) continue;
            int col;
            if (e instanceof AbstractClientPlayerEntity p) {
                if (MegaClient.skipPlayer(client, p)) continue;
                col = 0;
            } else if (e instanceof Monster) col = 0xFFFF4444;
            else if (e instanceof LivingEntity) col = 0xFF55DD55;
            else continue;
            float[] s = toScreen(e.getX() - px, e.getZ() - pz, cos, sin, k, cx, cy);
            if (!inside(s, x, y, size)) continue;
            if (col != 0) { dot(ctx, s, 1, col); continue; }
            // players: yellow arrow pointing where they face
            dot(ctx, s, 2, 0xFF000000);
            dot(ctx, s, 1, 0xFFFFD400);
            float fa = (float) Math.toRadians(e.getYaw());
            float[] t = toScreen(-Math.sin(fa) * 4 / k, Math.cos(fa) * 4 / k, cos, sin, k, 0, 0);
            ctx.fill((int) (s[0] + t[0]), (int) (s[1] + t[1]), (int) (s[0] + t[0]) + 2, (int) (s[1] + t[1]) + 2, 0xFFFFD400);
        }
        // stashes found now
        for (MegaClient.Target t : MegaClient.targets) {
            float[] s = toScreen(t.pos().getX() + 0.5 - px, t.pos().getZ() + 0.5 - pz, cos, sin, k, cx, cy);
            if (inside(s, x, y, size)) { dot(ctx, s, 2, 0xFF000000); dot(ctx, s, 1, MegaClient.readable(t.category().color)); }
        }
        // waypoints and deaths
        for (MegaWaypoints.Waypoint w : MegaWaypoints.here(client)) {
            float[] s = toScreen(w.x + 0.5 - px, w.z + 0.5 - pz, cos, sin, k, cx, cy);
            s[0] = Math.max(x + 2, Math.min(x + size - 3, s[0]));
            s[1] = Math.max(y + 2, Math.min(y + size - 3, s[1])); // pinned to the edge when far away
            dot(ctx, s, 2, 0xFF000000);
            dot(ctx, s, 1, w.color);
        }
        // you
        ctx.fill(cx - 1, cy - 3, cx + 2, cy + 3, 0xFFFFFFFF);
        ctx.fill(cx - 2, cy, cx + 3, cy + 3, 0xFFFFFFFF);
        ctx.disableScissor();

        String layer = LAYERS[Math.clamp(cfg.minimapLayer, 0, 2)];
        ctx.drawTextWithShadow(tr, layer, x + 2, y + 2, 0xFFFFFFFF);
        String pos = client.player.getBlockX() + ", " + client.player.getBlockY() + ", " + client.player.getBlockZ();
        ctx.fill(x, y + size, x + size, y + size + 10, 0x90000000);
        ctx.drawCenteredTextWithShadow(tr, tr.trimToWidth(pos, size), cx, y + size + 1, 0xFFDDDDDD);
        return y + size + 10;
    }

    static float[] toScreen(double dx, double dz, float cos, float sin, float k, int cx, int cy) {
        return new float[]{cx + (float) (dx * cos - dz * sin) * k, cy + (float) (dx * sin + dz * cos) * k};
    }

    private static boolean inside(float[] s, int x, int y, int size) {
        return s[0] >= x + 1 && s[0] < x + size - 1 && s[1] >= y + 1 && s[1] < y + size - 1;
    }

    private static void dot(DrawContext ctx, float[] s, int r, int col) {
        int ix = (int) s[0], iy = (int) s[1];
        ctx.fill(ix - r, iy - r, ix + r + 1, iy + r + 1, col);
    }

    // ---------------- saving ----------------

    private static Path file(String key) {
        return DIR.resolve(key.toLowerCase().replaceAll("[^a-z0-9._-]", "_") + ".bin");
    }

    private static Map<Long, byte[]> load(String key) {
        Map<Long, byte[]> m = new HashMap<>();
        Path f = file(key);
        if (!Files.exists(f)) return m;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != 1) return m;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                byte[] b = new byte[256];
                in.readFully(b);
                m.put(k, b);
            }
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Bad map file {}, starting fresh: {}", f, e.toString());
        }
        return m;
    }

    static void save() {
        if (dirty.isEmpty()) return;
        List<String> keys = new ArrayList<>(dirty);
        dirty.clear();
        for (String key : keys) {
            Map<Long, byte[]> m = memory.get(key);
            if (m == null) continue;
            try {
                Files.createDirectories(DIR);
                try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file(key)))))) {
                    out.writeInt(1);
                    out.writeInt(m.size());
                    for (Map.Entry<Long, byte[]> e : m.entrySet()) { out.writeLong(e.getKey()); out.write(e.getValue()); }
                }
            } catch (Exception e) {
                MegaClient.LOGGER.warn("Could not save map: {}", e.toString());
            }
        }
    }
}
