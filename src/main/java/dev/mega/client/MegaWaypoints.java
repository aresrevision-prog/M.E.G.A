package dev.mega.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Waypoints and your last deaths, saved per server / world in config/mega_waypoints.json.
 * Shown as a beam + box in the world and a list on the HUD. Info only: no teleporting.
 */
public final class MegaWaypoints {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mega_waypoints.json");
    static final int[] COLOURS = {0xFFFFFFFF, 0xFFFFD400, 0xFF55FFFF, 0xFFFF55FF, 0xFF55FF55, 0xFFFF8800};
    static final int DEATH_COLOUR = 0xFFFF5555;

    public static final class Waypoint {
        public String name;
        public int x, y, z;
        public String dim;
        public boolean death;
        public boolean visible = true;
        public int color = 0xFFFFFFFF;

        Waypoint() {}

        Waypoint(String name, BlockPos p, String dim, boolean death, int color) {
            this.name = name; this.x = p.getX(); this.y = p.getY(); this.z = p.getZ();
            this.dim = dim; this.death = death; this.color = color;
        }

        double distanceTo(double px, double py, double pz) {
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static Map<String, List<Waypoint>> all = new LinkedHashMap<>();
    private static boolean loaded, wasDead;

    private MegaWaypoints() {}

    /** "server:play.example.net" or "world:My World": waypoints are kept apart per server and per world. */
    static String serverKey(MinecraftClient client) {
        ServerInfo e = client.getCurrentServerEntry();
        if (e != null && e.address != null) return "server:" + e.address.toLowerCase(Locale.ROOT);
        if (client.getServer() != null) return "world:" + client.getServer().getSaveProperties().getLevelName();
        return "other";
    }

    static String dim(MinecraftClient client) {
        return client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
    }

    /** This server's list (live, edit and then call save()). */
    static List<Waypoint> list(MinecraftClient client) {
        load();
        return all.computeIfAbsent(serverKey(client), k -> new ArrayList<>());
    }

    /** Visible waypoints in the dimension you're in. */
    static List<Waypoint> here(MinecraftClient client) {
        List<Waypoint> out = new ArrayList<>();
        if (client.world == null || !MegaClient.config.waypoints) return out;
        String d = dim(client);
        for (Waypoint w : list(client)) if (w.visible && d.equals(w.dim)) out.add(w);
        return out;
    }

    static Waypoint addHere(MinecraftClient client, String name) {
        List<Waypoint> l = list(client);
        long n = l.stream().filter(w -> !w.death).count();
        if (name == null || name.isBlank()) name = "Waypoint " + (n + 1);
        Waypoint w = new Waypoint(name.trim(), client.player.getBlockPos(), dim(client), false, COLOURS[(int) (n % COLOURS.length)]);
        l.add(w);
        save();
        return w;
    }

    /** Called every tick: records where you died (keeps the last few per server). */
    static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { wasDead = false; return; }
        boolean dead = client.player.isDead() || client.player.getHealth() <= 0;
        if (dead && !wasDead) {
            List<Waypoint> l = list(client);
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            l.add(new Waypoint("Death " + time, client.player.getBlockPos(), dim(client), true, DEATH_COLOUR));
            List<Waypoint> deaths = l.stream().filter(w -> w.death).toList();
            for (int i = 0; i < deaths.size() - MegaClient.config.deathsKept; i++) l.remove(deaths.get(i)); // oldest first
            save();
            MegaClient.LOGGER.info("[M.E.G.A] death saved at {}", client.player.getBlockPos());
        }
        wasDead = dead;
    }

    static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(PATH)) return;
        try (Reader r = Files.newBufferedReader(PATH)) {
            Map<String, List<Waypoint>> m = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, List<Waypoint>>>() {}.getType());
            if (m != null) {
                m.values().removeIf(java.util.Objects::isNull);
                for (List<Waypoint> l : m.values()) l.removeIf(w -> w == null || w.name == null || w.dim == null);
                all = m;
            }
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Bad waypoints file, starting empty: {}", e.toString());
        }
    }

    static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(all, w);
            }
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Could not save waypoints: {}", e.toString());
        }
    }
}
