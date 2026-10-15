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
    /** In X-ray, lava (orange) and water (blue) near you are outlined so you can see them through hidden blocks. */
    public boolean xrayFluids = true;
    public int xrayGhostRange = 12;
    public int xrayGhostMax = 6000;
    /** Players the mod never detects (no line, not listed, no warning). Case-insensitive. */
    public java.util.List<String> ignoredPlayers = new java.util.ArrayList<>(java.util.List.of("IzzosBro2013"));
    public int playerRange = 1250;

    // Waypoints (B adds one where you stand) and your last deaths
    public boolean waypoints = true;
    /** G: only show stashes buried underground. */
    public boolean deepScan = false;
    /** Three radars (sky / ground / below 0), bottom right. */
    public boolean radar = true;
    public int radarRange = 1000;
    public int radarSize = 64;
    /** Top-right corner: two slots, each "minimap", "blocks" (Blocks seen), "radars" or "none". */
    public String cornerSlot1 = "minimap";
    public String cornerSlot2 = "blocks";
    public int minimapSize = 96;
    /** 0 = surface, 1 = cave (your level), 2 = below 0. N cycles it. */
    public int minimapLayer = 0;
    /** Remember every stash you pass (big map, M). */
    public boolean stashMemory = true;
    /** Fading line where each player has been. */
    public boolean trails = true;
    public int trailSeconds = 180;
    /** Where you last saw each player (HUD + big map + grey beam). */
    public boolean lastSeen = true;
    public boolean joinAlerts = true;
    /** Danger alert: very dangerous mobs near you (banner, red line, bell). */
    public boolean dangerAlert = true;
    public boolean dangerSound = true;
    public int dangerRange = 256;
    public java.util.List<String> dangerMobs = new java.util.ArrayList<>(java.util.List.of(
        "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian", "minecraft:ender_dragon",
        "minecraft:ravager", "minecraft:evoker", "minecraft:piglin_brute"));
    /** Player list shows armour, totem and held item. */
    public boolean gearCheck = true;
    /** While flying with an elytra: speed / height / compass bar with stashes and waypoints on it. */
    public boolean elytraHelper = true;
    public boolean signsAdded = false;
    // Portals & beds (violet)
    public boolean portals = true;
    public java.util.List<String> portalBlocks = new java.util.ArrayList<>(defaultPortals());
    public int deathsKept = 3;

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
    public java.util.List<String> oreBlocks = new java.util.ArrayList<>(DEFAULT_ORES);
    // Every colour has its own editable block list (panel: press 0 -> Edit blocks).
    public java.util.List<String> spawnerBlocks = new java.util.ArrayList<>(DEFAULT_SPAWNERS);
    static final java.util.List<String> DEFAULT_SPAWNERS = java.util.List.of("minecraft:spawner");
    public java.util.List<String> shulkerBlocks = new java.util.ArrayList<>(DEFAULT_SHULKERS);
    static final java.util.List<String> DEFAULT_SHULKERS = shulkers();
    public java.util.List<String> storageBlocks = new java.util.ArrayList<>(DEFAULT_STORAGE);
    static final java.util.List<String> DEFAULT_STORAGE = java.util.List.of(
        "minecraft:chest", "minecraft:barrel", "minecraft:hopper", "minecraft:crafter", "minecraft:dispenser", "minecraft:dropper");
    public java.util.List<String> farmPlants = new java.util.ArrayList<>(DEFAULT_FARM_PLANTS);
    static final java.util.List<String> DEFAULT_FARM_PLANTS = java.util.List.of(
        "minecraft:kelp", "minecraft:kelp_plant", "minecraft:sugar_cane", "minecraft:cactus",
        "minecraft:bamboo", "minecraft:pumpkin", "minecraft:melon");
    static final java.util.List<String> DEFAULT_ORES = java.util.List.of(
        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:emerald_ore",
        "minecraft:gold_ore", "minecraft:deepslate_gold_ore");
    // Lights underground (pale yellow): only counted when buried 4+ blocks under the surface
    public boolean lights = true;
    public java.util.List<String> lightBlocks = new java.util.ArrayList<>(DEFAULT_LIGHTS);
    static final java.util.List<String> DEFAULT_LIGHTS = java.util.List.of(
        "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:copper_torch", "minecraft:copper_wall_torch",
        "minecraft:lantern", "minecraft:soul_lantern", "minecraft:glowstone", "minecraft:sea_lantern",
        "minecraft:jack_o_lantern", "minecraft:redstone_lamp", "minecraft:end_rod", "minecraft:copper_lantern",
        "minecraft:ochre_froglight", "minecraft:verdant_froglight", "minecraft:pearlescent_froglight",
        "minecraft:campfire", "minecraft:soul_campfire");
    // built_block (white): blocks that never generate naturally
    public boolean built = true;
    public java.util.List<String> builtBlocks = new java.util.ArrayList<>(defaultBuilt());
    // Base signs (pink): trading halls, animal pens, tamed pets, named mobs
    public boolean baseSigns = true;
    /** Shulker items: full contents in tooltips; dropped/framed/held ones listed with what is inside. */
    public boolean shulkerPeek = true;
    /** Hitbox outlines (like F3+B) for players, mobs and vehicles near you, through walls. Look only. */
    public boolean hitboxes = true;
    public int hitboxRange = 64;
    public java.util.List<String> baseSignTypes = new java.util.ArrayList<>(DEFAULT_BASE_SIGNS);
    static final java.util.List<String> DEFAULT_BASE_SIGNS = java.util.List.of(
        "mega:trading_halls", "mega:animal_pens", "mega:tamed_pets", "mega:named_mobs", "mega:signs");

    // "Valuable blocks" (blue)
    public java.util.List<String> valuableBlocks = new java.util.ArrayList<>(DEFAULT_VALUABLES);
    static final java.util.List<String> DEFAULT_VALUABLES = java.util.List.of(
        "minecraft:ender_chest", "minecraft:enchanting_table", "minecraft:beacon",
        "minecraft:netherite_block", "minecraft:ancient_debris", "minecraft:diamond_block",
        "minecraft:emerald_block", "minecraft:gold_block",
        "minecraft:quartz_block", "minecraft:chiseled_quartz_block", "minecraft:quartz_pillar",
        "minecraft:quartz_bricks", "minecraft:smooth_quartz",
        "minecraft:deepslate_emerald_ore", "minecraft:deepslate_coal_ore");
    // "Workstations" (dark green)
    public boolean workstations = true;
    public java.util.List<String> workstationBlocks = new java.util.ArrayList<>(DEFAULT_WORKSTATIONS);
    static final java.util.List<String> DEFAULT_WORKSTATIONS = java.util.List.of(
        "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
        "minecraft:crafting_table", "minecraft:smithing_table", "minecraft:grindstone",
        "minecraft:stonecutter", "minecraft:loom", "minecraft:cartography_table", "minecraft:fletching_table",
        "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace");
    static final int CURRENT_VERSION = 9;

    /** Every block id that never generates in the world on its own (checked against the game's block list). */
    static java.util.List<String> defaultPortals() {
        java.util.List<String> l = new java.util.ArrayList<>(java.util.List.of("minecraft:nether_portal", "minecraft:end_portal",
            "minecraft:end_portal_frame", "minecraft:end_gateway", "minecraft:respawn_anchor"));
        for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) l.add("minecraft:" + c + "_bed");
        return l;
    }

    static java.util.List<String> defaultBuilt() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (net.minecraft.util.Identifier id : net.minecraft.registry.Registries.BLOCK.getIds()) {
                if (!id.getNamespace().equals("minecraft")) continue;
                String p = id.getPath();
                boolean b = p.endsWith("_concrete") || p.endsWith("_concrete_powder") || p.endsWith("_glazed_terracotta")
                    || p.endsWith("_wool") || p.endsWith("_carpet") && !p.equals("moss_carpet") && !p.equals("pale_moss_carpet")
                    || p.equals("glass") || p.equals("glass_pane") || p.equals("tinted_glass") || p.endsWith("_stained_glass")
                    || p.endsWith("_stained_glass_pane")
                    || p.startsWith("polished_andesite") || p.startsWith("polished_diorite") || p.startsWith("polished_granite")
                    || p.startsWith("polished_deepslate") || p.startsWith("polished_tuff")
                    || p.startsWith("smooth_stone");
                if (b) out.add(id.toString());
            }
        } catch (Throwable t) { /* registry not ready: start empty, Reset fills it */ }
        java.util.Collections.sort(out);
        return out;
    }

    private static java.util.List<String> shulkers() {
        java.util.List<String> l = new java.util.ArrayList<>(java.util.List.of("minecraft:shulker_box"));
        for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) l.add("minecraft:" + c + "_shulker_box");
        return java.util.List.copyOf(l);
    }
    /** Bumped when defaults change, so old config files get the new block lists. */
    public int configVersion = 0;

    // Tracers (lines from crosshair) + box outlines, drawn for the nearest targets
    public boolean tracers = true;
    public boolean boxes = true;
    public boolean tracePlayers = true;
    public int maxTracers = 8;
    /** List and tracers show stash blocks before natural ores (ores still counted and shown, just after). */
    public boolean oresLast = true;
    public double lineWidth = 2.0;

    // Farm alert: banner at the top when more than farmThreshold dropped items are moving within farmRange
    public boolean farmAlert = true;
    public int farmThreshold = 32;
    public int farmRange = 1250;
    /** Dropped items that never count towards the moving-items farm alert. */
    public java.util.List<String> ignoredItems = new java.util.ArrayList<>(java.util.List.of(
        "minecraft:stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
        "minecraft:deepslate", "minecraft:gravel"));

    /** Single blocks switched off in the panel, e.g. "minecraft:hopper", plus "underground_farm". */
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
        if (cfg.configVersion == 8) { migrate9(cfg); cfg.configVersion = CURRENT_VERSION; }
        else if (cfg.configVersion < CURRENT_VERSION) {
            cfg.disabledTargets = new java.util.ArrayList<>();
            cfg.valuableBlocks = new java.util.ArrayList<>(DEFAULT_VALUABLES);
            cfg.workstationBlocks = new java.util.ArrayList<>(DEFAULT_WORKSTATIONS);
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

    /** v9: every colour gets its own block list; per-block switches use full block ids. Keeps the player's edits. */
    private static void migrate9(MegaConfig cfg) {
        cfg.clamp();
        java.util.List<String> v = new java.util.ArrayList<>(java.util.List.of("minecraft:ender_chest", "minecraft:enchanting_table", "minecraft:beacon"));
        for (String id : cfg.valuableBlocks) if (!v.contains(id)) v.add(id);
        cfg.valuableBlocks = v;
        for (String id : java.util.List.of("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace"))
            if (!cfg.workstationBlocks.contains(id)) cfg.workstationBlocks.add(id);
        java.util.List<String> off = new java.util.ArrayList<>();
        for (String k : cfg.disabledTargets) {
            switch (k) {
                case "valuable_blocks" -> cfg.valuables = false;
                case "workstations" -> cfg.workstations = false;
                case "mob_spawner" -> off.add("minecraft:spawner");
                case "shulker_box" -> off.addAll(DEFAULT_SHULKERS);
                case "underground_farm" -> off.add(k);
                case "plant_farm" -> off.addAll(DEFAULT_FARM_PLANTS);
                default -> {
                    off.add("minecraft:" + k);
                    if (k.endsWith("_ore")) off.add("minecraft:deepslate_" + k);
                }
            }
        }
        cfg.disabledTargets = off;
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
        if (workstationBlocks == null) workstationBlocks = new java.util.ArrayList<>();
        if (valuableBlocks == null) valuableBlocks = new java.util.ArrayList<>();
        if (spawnerBlocks == null) spawnerBlocks = new java.util.ArrayList<>(DEFAULT_SPAWNERS);
        if (shulkerBlocks == null) shulkerBlocks = new java.util.ArrayList<>(DEFAULT_SHULKERS);
        if (storageBlocks == null) storageBlocks = new java.util.ArrayList<>(DEFAULT_STORAGE);
        deathsKept = Math.clamp(deathsKept, 1, 20);
        radarRange = Math.clamp(radarRange, 16, 1250);
        hitboxRange = Math.clamp(hitboxRange, 8, 256);
        minimapSize = Math.clamp(minimapSize, 48, 200);
        minimapLayer = Math.clamp(minimapLayer, 0, 2);
        trailSeconds = Math.clamp(trailSeconds, 10, 1800);
        dangerRange = Math.clamp(dangerRange, 16, 1250);
        if (dangerMobs == null) dangerMobs = new java.util.ArrayList<>();
        if (!java.util.List.of("minimap", "blocks", "radars", "none").contains(cornerSlot1)) cornerSlot1 = "minimap";
        if (!java.util.List.of("minimap", "blocks", "radars", "none").contains(cornerSlot2)) cornerSlot2 = "blocks";
        if (portalBlocks == null) portalBlocks = new java.util.ArrayList<>(defaultPortals());
        radarSize = Math.clamp(radarSize, 40, 128);
        if (lightBlocks == null) lightBlocks = new java.util.ArrayList<>(DEFAULT_LIGHTS);
        if (builtBlocks == null || builtBlocks.isEmpty() && built) builtBlocks = new java.util.ArrayList<>(defaultBuilt());
        if (baseSignTypes == null) baseSignTypes = new java.util.ArrayList<>(DEFAULT_BASE_SIGNS);
        if (!signsAdded) { if (!baseSignTypes.contains("mega:signs")) baseSignTypes.add("mega:signs"); signsAdded = true; }
        if (farmPlants == null) farmPlants = new java.util.ArrayList<>(DEFAULT_FARM_PLANTS);
    }
}
