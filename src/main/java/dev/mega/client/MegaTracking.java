package dev.mega.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.vehicle.AbstractChestBoatEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Things M.E.G.A remembers over time: stashes you've passed (stash memory), where players walked (trails), where you
 * last saw each player, who joined/left. Plus hidden storage entities (chest minecarts, donkeys with chests...).
 */
public final class MegaTracking {
    private static final Gson GSON = new Gson();
    private static final Path MEMORY = FabricLoader.getInstance().getConfigDir().resolve("mega_memory.json");

    // ---------- stash memory ----------
    public static final class Mem {
        public String label;
        public int x, y, z, color;
        public long seen;
        Mem() {}
        Mem(String label, int x, int y, int z, int color) { this.label = label; this.x = x; this.y = y; this.z = z; this.color = color; seen = System.currentTimeMillis(); }
    }
    /** server -> dimension -> packed pos -> stash. */
    private static Map<String, Map<String, Map<Long, Mem>>> memory = new LinkedHashMap<>();
    private static boolean loaded, dirty;
    private static int saveTimer;

    // ---------- trails / last seen / join-leave ----------
    record Point(double x, double y, double z, long t) {}
    static final Map<UUID, ArrayDeque<Point>> trails = new HashMap<>();
    record Seen(String name, double x, double y, double z, String dim, long t) {}
    static final Map<String, Seen> lastSeen = new LinkedHashMap<>();
    private static Set<String> online;
    private static int timer;
    private static boolean wasInWorld;

    private MegaTracking() {}

    static Map<Long, Mem> stashes(MinecraftClient client) {
        load();
        return memory.computeIfAbsent(MegaWaypoints.serverKey(client), k -> new LinkedHashMap<>())
            .computeIfAbsent(MegaWaypoints.dim(client), k -> new LinkedHashMap<>());
    }

    /** After every full scan: remember every placed stash block / base sign that was found. */
    static void remember(MinecraftClient client, List<MegaClient.Target> found) {
        if (!MegaClient.config.stashMemory || client.world == null) return;
        Map<Long, Mem> m = stashes(client);
        for (MegaClient.Target t : found) {
            MegaClient.Category c = t.category();
            if (c == MegaClient.Category.ORE || c == MegaClient.Category.FARM || MegaClient.natural(t.label(), c.color)) continue;
            if (t.label().contains("HOLDS SHULKER") || t.label().startsWith("DROPPED_") || t.label().startsWith("TAMED_")
                || t.label().startsWith("NAMED_")) continue; // things that move
            long key = t.pos().asLong();
            Mem old = m.get(key);
            if (old == null) { m.put(key, new Mem(t.label(), t.pos().getX(), t.pos().getY(), t.pos().getZ(), c.color)); dirty = true; }
            else old.seen = System.currentTimeMillis();
        }
        if (m.size() > 20000) { // keep the newest
            List<Map.Entry<Long, Mem>> l = new ArrayList<>(m.entrySet());
            l.sort((a, b) -> Long.compare(a.getValue().seen, b.getValue().seen));
            for (int i = 0; i < l.size() - 20000; i++) m.remove(l.get(i).getKey());
        }
    }

    /** Hidden storage the block scan can't see: chest/hopper minecarts, chest boats, donkeys/mules/llamas with chests. */
    static void scanStorageEntities(MinecraftClient client, List<MegaClient.Target> found, Map<MegaClient.Category, Integer> counts) {
        if (!MegaClient.Category.STORAGE.enabled(MegaClient.config) || MegaClient.config.disabledTargets.contains("mega:storage_entities")) return;
        double maxSq = (double) MegaClient.config.maxDistance * MegaClient.config.maxDistance;
        for (Entity e : client.world.getEntities()) {
            boolean hit = e instanceof ChestMinecartEntity || e instanceof HopperMinecartEntity || e instanceof AbstractChestBoatEntity
                || e instanceof AbstractDonkeyEntity d && d.hasChest();
            if (!hit) continue;
            double sq = client.player.squaredDistanceTo(e);
            if (sq > maxSq) continue;
            String name = Registries.ENTITY_TYPE.getId(e.getType()).getPath().toUpperCase(Locale.ROOT);
            if (e instanceof AbstractDonkeyEntity) name += "_WITH_CHEST";
            found.add(new MegaClient.Target(MegaClient.Category.STORAGE, name, e.getBlockPos(), Math.sqrt(sq)));
            counts.merge(MegaClient.Category.STORAGE, 1, Integer::sum);
        }
    }

    static void tick(MinecraftClient client) {
        MegaConfig cfg = MegaClient.config;
        boolean inWorld = client.world != null && client.player != null;
        if (!inWorld) {
            if (wasInWorld) { save(); trails.clear(); online = null; }
            wasInWorld = false;
            return;
        }
        wasInWorld = true;
        if (++saveTimer >= 1200) { saveTimer = 0; save(); }
        if (++timer < 10) return;
        timer = 0;
        long now = System.currentTimeMillis();
        String dim = MegaWaypoints.dim(client);

        // trails + last seen
        Set<UUID> here = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (MegaClient.skipPlayer(client, p)) continue;
            here.add(p.getUuid());
            lastSeen.put(p.getName().getString(), new Seen(p.getName().getString(), p.getX(), p.getY(), p.getZ(), dim, now));
            if (!cfg.trails) continue;
            ArrayDeque<Point> q = trails.computeIfAbsent(p.getUuid(), k -> new ArrayDeque<>());
            Point last = q.peekLast();
            if (last == null || sq(last.x - p.getX()) + sq(last.y - p.getY()) + sq(last.z - p.getZ()) > 0.25)
                q.addLast(new Point(p.getX(), p.getY() + 0.2, p.getZ(), now));
        }
        long keep = cfg.trailSeconds * 1000L;
        trails.entrySet().removeIf(e -> {
            ArrayDeque<Point> q = e.getValue();
            while (!q.isEmpty() && now - q.peekFirst().t() > keep) q.pollFirst();
            return q.isEmpty();
        });

        // join / leave alerts (tab list)
        if (cfg.joinAlerts && client.getNetworkHandler() != null) {
            Set<String> names = new HashSet<>();
            for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) names.add(e.getProfile().name());
            String me = client.player.getName().getString();
            if (online != null) {
                for (String n : names) if (!online.contains(n) && !n.equals(me) && !ignored(n))
                    client.player.sendMessage(Text.literal("[M.E.G.A] ").formatted(Formatting.GOLD)
                        .append(Text.literal(n + " joined").formatted(Formatting.GREEN)), false);
                for (String n : online) if (!names.contains(n) && !n.equals(me) && !ignored(n))
                    client.player.sendMessage(Text.literal("[M.E.G.A] ").formatted(Formatting.GOLD)
                        .append(Text.literal(n + " left").formatted(Formatting.RED)), false);
            }
            online = names;
        }
    }

    private static boolean ignored(String n) {
        for (String s : MegaClient.config.ignoredPlayers) if (s.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** Players you saw recently who aren't in view now (newest first, this dimension). */
    static List<Seen> recentlyGone(MinecraftClient client, int max) {
        Set<String> visible = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) visible.add(p.getName().getString());
        String dim = MegaWaypoints.dim(client);
        long now = System.currentTimeMillis();
        List<Seen> out = new ArrayList<>();
        for (Seen s : lastSeen.values())
            if (!visible.contains(s.name()) && s.dim().equals(dim) && now - s.t() < 30 * 60_000L) out.add(s);
        out.sort((a, b) -> Long.compare(b.t(), a.t()));
        return out.size() > max ? out.subList(0, max) : out;
    }

    static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(MEMORY)) return;
        try (Reader r = Files.newBufferedReader(MEMORY)) {
            Map<String, Map<String, Map<Long, Mem>>> m = GSON.fromJson(r,
                new TypeToken<LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<Long, Mem>>>>() {}.getType());
            if (m != null) memory = m;
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Bad stash memory file, starting empty: {}", e.toString());
        }
    }

    static void save() {
        MegaMap.save();
        if (!dirty) return;
        dirty = false;
        try (Writer w = Files.newBufferedWriter(MEMORY)) {
            GSON.toJson(memory, w);
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Could not save stash memory: {}", e.toString());
        }
    }

    private static double sq(double v) { return v * v; }
}
