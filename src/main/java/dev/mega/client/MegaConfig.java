package dev.mega.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON config stored at config/mega_client.json. Missing fields fall back to defaults. */
public final class MegaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mega_client.json");

    // HUD
    public boolean hudEnabled = true;
    public int hudX = 8;
    public int hudY = 8;
    public int maxLines = 8;
    /** "Blocks seen" panel in the top-right corner: how many of each block type are in range. */
    public boolean blockCountPanel = true;
    public int blockCountLines = 15;

    // Players
    public boolean playerWarning = true;
    public boolean playerList = true;
    public boolean showInvisiblePlayers = false;
    /** Box outline (through walls) round invisible (magenta) and crouching (yellow) players. */
    public boolean outlineSneaky = true;
    /** X-ray: other blocks invisible; ores / valuables / spawners / storage stay solid (real textures) with coloured edges. */
    public boolean xray = false;
    /** Most x-ray blocks outlined at once (nearest first). */
    public int xrayMax = 3000;
    /** Coloured edges round X-ray blocks (off: they just show with their real textures). */
    public boolean xrayEdges = false;
    /** Hidden blocks next to caves/air keep an outline in their own colour, so you can see the terrain shape. */
    public boolean xrayGhost = true;
    public int xrayGhostRange = 12;
    public int xrayGhostMax = 6000;
    /** Players the mod never detects (no line, not listed, no warning). Case-insensitive. */
    public java.util.List<String> ignoredPlayers = new java.util.ArrayList<>(java.util.List.of("IzzosBro2013"));
    public int playerRange = 1250;

    // Block targets
    public boolean spawners = true;
    public boolean shulkers = true;
    public boolean storage = true;
    public boolean valuables = true;
    public int maxDistance = 1250;
    public int scanIntervalTicks = 20;
    /** Chunks scanned per tick (spreads the work so there are no lag spikes). */
    public int chunksPerTick = 24;

    // Ore finder (loaded chunks only)
    public boolean ores = true;
    public int oreRange = 1250;
    public java.util.List<String> oreBlocks = new java.util.ArrayList<>(java.util.List.of(
        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore",
        "minecraft:gold_ore", "minecraft:deepslate_gold_ore"));
    // "Valuable blocks" group (blue, ONE on/off button). Furnaces, smokers and blast furnaces are always in it too.
    public java.util.List<String> valuableBlocks = new java.util.ArrayList<>(DEFAULT_VALUABLES);
    static final java.util.List<String> DEFAULT_VALUABLES = java.util.List.of(
        "minecraft:netherite_block", "minecraft:ancient_debris", "minecraft:diamond_block",
        "minecraft:emerald_block", "minecraft:gold_block",
        "minecraft:quartz_block", "minecraft:chiseled_quartz_block", "minecraft:quartz_pillar",
        "minecraft:quartz_bricks", "minecraft:smooth_quartz",
        "minecraft:deepslate_emerald_ore", "minecraft:deepslate_coal_ore",
        "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
        "minecraft:crafting_table", "minecraft:smithing_table", "minecraft:grindstone",
        "minecraft:stonecutter", "minecraft:loom", "minecraft:cartography_table", "minecraft:fletching_table");
    static final int CURRENT_VERSION = 7;
    /** Bumped when defaults change, so old config files get the new block lists. */
    public int configVersion = 0;

    // Tracers (lines from crosshair) + box outlines, drawn for the nearest targets
    public boolean tracers = true;
    public boolean boxes = true;
    public boolean tracePlayers = true;
    public int maxTracers = 8;
    public double lineWidth = 2.0;

    // Farm alert: banner at the top when more than farmThreshold dropped items are moving within farmRange
    public boolean farmAlert = true;
    public int farmThreshold = 32;
    public int farmRange = 1250;
    /** Dropped items that never count towards the moving-items farm alert. */
    public java.util.List<String> ignoredItems = new java.util.ArrayList<>(java.util.List.of(
        "minecraft:stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
        "minecraft:deepslate", "minecraft:gravel"));

    /** Individual targets switched off in the panel, e.g. "hopper", "diamond_ore". */
    public java.util.List<String> disabledTargets = new java.util.ArrayList<>();

    public static MegaConfig load() {
        MegaConfig cfg = null;
        if (Files.exists(PATH)) {
            try (Reader r = Files.newBufferedReader(PATH)) {
                cfg = GSON.fromJson(r, MegaConfig.class);
            } catch (Exception e) {
                MegaClient.LOGGER.warn("Bad config, using defaults: {}", e.toString());
            }
        }
        if (cfg == null) cfg = new MegaConfig();
        if (cfg.configVersion < CURRENT_VERSION) {
            cfg.valuableBlocks = new java.util.ArrayList<>(DEFAULT_VALUABLES);
            cfg.oreBlocks = new java.util.ArrayList<>(new MegaConfig().oreBlocks);
            if (cfg.disabledTargets != null) cfg.disabledTargets.removeIf(k -> k.endsWith("_block") || k.equals("ancient_debris"));
            if (cfg.configVersion < 5) { // v5: 1250-block search range
                cfg.maxDistance = cfg.oreRange = cfg.farmRange = cfg.playerRange = 1250;
            }
            cfg.configVersion = CURRENT_VERSION;
        }
        cfg.clamp();
        cfg.save(); // writes defaults / any new fields
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            MegaClient.LOGGER.warn("Could not save config: {}", e.toString());
        }
    }

    private void clamp() {
        hudX = Math.max(0, hudX);
        hudY = Math.max(0, hudY);
        maxLines = Math.clamp(maxLines, 1, 30);
        blockCountLines = Math.clamp(blockCountLines, 1, 40);
        playerRange = Math.clamp(playerRange, 8, 1250);
        maxDistance = Math.clamp(maxDistance, 8, 1250);
        scanIntervalTicks = Math.clamp(scanIntervalTicks, 5, 200);
        oreRange = Math.clamp(oreRange, 8, 1250);
        chunksPerTick = Math.clamp(chunksPerTick, 1, 400);
        maxTracers = Math.clamp(maxTracers, 0, 50);
        xrayMax = Math.clamp(xrayMax, 100, 20000);
        xrayGhostRange = Math.clamp(xrayGhostRange, 4, 32);
        xrayGhostMax = Math.clamp(xrayGhostMax, 100, 30000);
        lineWidth = Math.clamp(lineWidth, 0.5, 10.0);
        if (oreBlocks == null) oreBlocks = new java.util.ArrayList<>();
        farmThreshold = Math.clamp(farmThreshold, 1, 1000);
        farmRange = Math.clamp(farmRange, 8, 1250);
        if (ignoredItems == null) ignoredItems = new java.util.ArrayList<>();
        if (disabledTargets == null) disabledTargets = new java.util.ArrayList<>();
        if (ignoredPlayers == null) ignoredPlayers = new java.util.ArrayList<>();
        if (valuableBlocks == null) valuableBlocks = new java.util.ArrayList<>();
    }
}
