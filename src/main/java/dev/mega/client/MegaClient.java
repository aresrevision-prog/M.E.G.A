package dev.mega.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;

public final class MegaClient implements ClientModInitializer {
    public static final String MOD_ID = "mega_client";
    public static final Logger LOGGER = LoggerFactory.getLogger("M.E.G.A");

    static MegaConfig config;
    static String version = "?";

    /** Results of the latest scan, sorted nearest-first. Swapped atomically on the client thread. */
    static List<Target> targets = List.of();
    /** X-ray: EVERY matching block (not grouped), nearest first, capped at config.xrayMax. */
    static List<Target> xrayBlocks = List.of();
    static List<PlayerInfo> players = List.of();
    static final Map<Category, Integer> counts = new EnumMap<>(Category.class);
    /** Per block type counts for the "Blocks seen" corner panel: label, count, colour. Sorted biggest first. */
    record BlockCount(String label, int count, int color) {}
    static List<BlockCount> blockCounts = List.of();
    static boolean playerVisible;

    /** Farm alert: moving dropped items near you. */
    static int farmCount;
    static double farmX, farmY, farmZ;
    private static Map<ItemEntity, double[]> lastItemPos = new java.util.IdentityHashMap<>();
    /** Plain blocks (no block entity) to find by scanning block states: ores and expensive blocks. */
    private static Map<Block, Category> scanBlocks = Map.of();
    private static Set<String> ignored = Set.of();
    private static Set<net.minecraft.item.Item> ignoredItems = Set.of();
    private static Set<String> disabled = Set.of();
    private static final Map<BlockEntityType<?>, String> BE_KEYS = new java.util.HashMap<>();
    private static final Map<Block, String> LABELS = new java.util.HashMap<>();

    private static KeyBinding reloadKey;
    private static KeyBinding settingsKey;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        loadConfig();
        version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        settingsKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.mega_client.settings", GLFW.GLFW_KEY_0, cat));
        reloadKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.mega_client.reload_config", GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientTickEvents.END_CLIENT_TICK.register(MegaClient::onTick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.of(MOD_ID, "hud"), (ctx, tick) -> MegaHud.render(MinecraftClient.getInstance(), ctx));

        WorldRenderEvents.END_MAIN.register(MegaTracers::render);

        LOGGER.info("M.E.G.A Client {} loaded", version);
    }

    private static void onTick(MinecraftClient client) {
        while (settingsKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new MegaScreen(null));
        }
        while (reloadKey.wasPressed()) {
            loadConfig();
            tickCounter = Integer.MAX_VALUE - 1; // rescan next tick
        job = null;
            message(client, "Config reloaded", Formatting.AQUA);
        }

        MegaXray.tick(client);
        MegaXray.scanGhosts(client);

        if (client.world == null || client.player == null || !config.hudEnabled) {
            targets = List.of();
            players = List.of();
            counts.clear();
            playerVisible = false;
            farmCount = 0;
            lastItemPos.clear();
            xrayBlocks = List.of();
            blockCounts = List.of();
            job = null;
            return;
        }

        scanPlayers(client);
        if (job != null) {
            continueScan(client);
        } else if (++tickCounter >= config.scanIntervalTicks) {
            tickCounter = 0;
            startScan(client);
            continueScan(client);
        }
    }

    private static void loadConfig() {
        config = MegaConfig.load();
        applyConfig();
    }

    /** Rebuilds lookups from the current config (called after load and when the settings panel closes). */
    static void applyConfig() {
        tickCounter = Integer.MAX_VALUE - 1; // rescan next tick
        disabled = new HashSet<>(config.disabledTargets);
        Set<net.minecraft.item.Item> items = new HashSet<>();
        for (String id : config.ignoredItems) {
            Identifier ident = Identifier.tryParse(id);
            if (ident != null) Registries.ITEM.getOptionalValue(ident).ifPresent(items::add);
        }
        ignoredItems = items;
        Set<String> ig = new HashSet<>();
        for (String n : config.ignoredPlayers) if (n != null) ig.add(n.trim().toLowerCase(Locale.ROOT));
        ignored = ig;
        Map<Block, Category> map = new java.util.LinkedHashMap<>();
        addBlocks(map, config.oreBlocks, Category.ORE);
        addBlocks(map, config.valuableBlocks, Category.VALUABLE);
        addBlocks(map, config.workstationBlocks, Category.WORKSTATION);
        scanBlocks = map;
        MegaXray.apply(MinecraftClient.getInstance(), map.keySet());
    }

    private static void addBlocks(Map<Block, Category> map, List<String> ids, Category cat) {
        for (String id : ids) {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) { LOGGER.warn("Bad block id: {}", id); continue; }
            Registries.BLOCK.getOptionalValue(ident).ifPresentOrElse(b -> map.put(b, cat),
                () -> LOGGER.warn("Unknown block: {}", id));
        }
    }

    private static void message(MinecraftClient client, String msg, Formatting color) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[M.E.G.A] ").formatted(Formatting.GOLD)
                .append(Text.literal(msg).formatted(color)), true);
        }
    }

    private static void scanPlayers(MinecraftClient client) {
        if (!config.playerWarning && !config.playerList) {
            players = List.of();
            playerVisible = false;
            return;
        }
        double maxSq = (double) config.playerRange * config.playerRange;
        List<PlayerInfo> found = new ArrayList<>();
        boolean anyVisible = false;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (skipPlayer(client, p)) continue;
            if (p.isInvisible() && !config.showInvisiblePlayers) continue;
            double sq = client.player.squaredDistanceTo(p);
            if (sq > maxSq) continue;
            boolean visible = client.player.canSee(p);
            anyVisible |= visible;
            found.add(new PlayerInfo(p.getName().getString(), p.getX(), p.getZ(), Math.sqrt(sq), visible));
        }
        found.sort(Comparator.comparingDouble(PlayerInfo::distance));
        players = found;
        playerVisible = anyVisible;
    }

    /** True for players the mod must ignore: you (by reference or UUID), spectators, and names in ignoredPlayers. */
    static boolean skipPlayer(MinecraftClient client, AbstractClientPlayerEntity p) {
        if (p == client.player || p.getUuid().equals(client.player.getUuid()) || p.isSpectator()) return true;
        return ignored.contains(p.getName().getString().toLowerCase(Locale.ROOT));
    }

    /** Counts dropped items in range that are moving (have velocity, or moved since the last scan). */
    private static void scanFarm(MinecraftClient client) {
        if (!config.farmAlert) { farmCount = 0; lastItemPos.clear(); return; }
        double maxSq = (double) config.farmRange * config.farmRange;
        Map<ItemEntity, double[]> now = new java.util.IdentityHashMap<>();
        int moving = 0;
        double sx = 0, sy = 0, sz = 0;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof ItemEntity item)) continue;
            if (ignoredItems.contains(item.getStack().getItem())) continue;
            if (client.player.squaredDistanceTo(item) > maxSq) continue;
            double x = item.getX(), y = item.getY(), z = item.getZ();
            now.put(item, new double[]{x, y, z});
            double[] prev = lastItemPos.get(item);
            boolean moved = prev != null && ((x - prev[0]) * (x - prev[0]) + (y - prev[1]) * (y - prev[1])
                + (z - prev[2]) * (z - prev[2])) > 0.01;
            if (moved || item.getVelocity().lengthSquared() > 1.0e-4) {
                moving++;
                sx += x; sy += y; sz += z;
            }
        }
        lastItemPos = now;
        farmCount = moving > config.farmThreshold ? moving : 0;
        if (moving > 0) { farmX = sx / moving; farmY = sy / moving; farmZ = sz / moving; }
    }

    /** A scan spread over several ticks so a full render-distance scan never causes a lag spike. */
    private static final class ScanJob {
        final List<ChunkPos> chunks = new ArrayList<>();
        int index;
        final List<Target> found = new ArrayList<>();
        final List<Target> xray = new ArrayList<>();
        final Map<Category, Integer> counts = new EnumMap<>(Category.class);
        final Set<Long> seenVeins = new HashSet<>();
        final Map<String, BlockCount> blockCounts = new java.util.HashMap<>();
        ChunkPos center;
        double maxSq, oreSq, farmSq;
        int oreChunkRadius, farmChunkRadius;
        boolean scanOres, scanFarms;
    }

    private static ScanJob job;

    /** Search radius in blocks: the whole render distance, capped by the configured range. */
    private static double effectiveRange(MinecraftClient client, int configured) {
        return Math.min(configured, client.options.getClampedViewDistance() * 16 + 16);
    }

    private static void startScan(MinecraftClient client) {
        ScanJob j = new ScanJob();
        double max = effectiveRange(client, config.maxDistance);
        double ore = effectiveRange(client, config.oreRange);
        double farm = effectiveRange(client, config.farmRange);
        // Render distance only limits how far out SIDEWAYS chunks load; every loaded chunk has its full height
        // (bedrock to sky). So the 3D distance limit is the configured range, and the render distance only limits
        // which chunks get scanned. This is what lets you find things 100+ blocks straight below you.
        j.maxSq = (double) config.maxDistance * config.maxDistance;
        j.oreSq = (double) config.oreRange * config.oreRange;
        j.farmSq = (double) config.farmRange * config.farmRange;
        int chunkRadius = client.options.getClampedViewDistance();
        j.oreChunkRadius = Math.min(chunkRadius, (int) Math.ceil(ore / 16.0));
        j.farmChunkRadius = Math.min(chunkRadius, (int) Math.ceil(farm / 16.0));
        j.scanOres = (config.ores || config.valuables) && !scanBlocks.isEmpty();
        j.scanFarms = config.farmAlert && Category.FARM.enabled(config);
        j.center = client.player.getChunkPos();
        int r = Math.min(chunkRadius, (int) Math.ceil(max / 16.0) + 1);
        r = Math.max(r, Math.max(j.scanOres ? j.oreChunkRadius : 0, j.scanFarms ? j.farmChunkRadius : 0));
        for (int cx = j.center.x - r; cx <= j.center.x + r; cx++)
            for (int cz = j.center.z - r; cz <= j.center.z + r; cz++)
                j.chunks.add(new ChunkPos(cx, cz));
        final ChunkPos c = j.center;
        j.chunks.sort(Comparator.comparingInt(p -> Math.max(Math.abs(p.x - c.x), Math.abs(p.z - c.z)))); // nearest first
        job = j;
    }

    /** Processes up to chunksPerTick chunks; publishes results when the whole area is done. */
    private static void continueScan(MinecraftClient client) {
        ScanJob j = job;
        int end = Math.min(j.chunks.size(), j.index + config.chunksPerTick);
        for (; j.index < end; j.index++) {
            ChunkPos cp = j.chunks.get(j.index);
            WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk == null) continue;
            int dx = Math.abs(cp.x - j.center.x), dz = Math.abs(cp.z - j.center.z);

            for (BlockEntity be : chunk.getBlockEntities().values()) {
                Category category = categoryFor(be);
                if (category == null || !category.enabled(config)) continue;
                if (disabled.contains(beKey(be.getType()))) continue;
                if (isSecondChestHalf(be)) continue; // double chest = one entry

                BlockPos pos = be.getPos();
                double sq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (sq > j.maxSq) continue;

                Target bt = new Target(category, label(be.getCachedState().getBlock(), false), pos.toImmutable(), Math.sqrt(sq));
                j.found.add(bt);
                if (config.xray && config.xrayEdges) j.xray.add(bt);
                j.counts.merge(category, 1, Integer::sum);
                countBlock(bt.label(), category);
            }
            if (j.scanOres && dx <= j.oreChunkRadius && dz <= j.oreChunkRadius)
                scanOres(client, chunk, j.oreSq, j.found, j.counts, j.seenVeins, config.xray && config.xrayEdges ? j.xray : null);
            if (j.scanFarms && dx <= j.farmChunkRadius && dz <= j.farmChunkRadius)
                scanFarmBlocks(client, chunk, j.farmSq, j.found, j.counts);
        }
        if (j.index >= j.chunks.size()) {
            // Stash blocks come first (storage, spawners, shulkers, valuables...), natural ores after, so ores
            // (gold is everywhere in real worlds) don't take all the tracer lines away from a stash.
            j.found.sort(Comparator.comparingInt((Target t) -> config.oresLast && t.category() == Category.ORE ? 1 : 0)
                .thenComparingDouble(Target::distance));
            targets = j.found;
            j.xray.sort(Comparator.comparingDouble(Target::distance));
            xrayBlocks = j.xray.size() > config.xrayMax ? new ArrayList<>(j.xray.subList(0, config.xrayMax)) : j.xray;
            counts.clear();
            counts.putAll(j.counts);
            List<BlockCount> bc = new ArrayList<>(j.blockCounts.values());
            bc.sort(Comparator.comparingInt(BlockCount::count).reversed().thenComparing(BlockCount::label));
            blockCounts = bc;
            job = null;
            scanFarm(client);
        }
    }

    /** Scans one chunk's block states for ores / expensive blocks. Sections without any are skipped via their palette (cheap). */
    private static void scanOres(MinecraftClient client, WorldChunk chunk, double maxSq,
                                 List<Target> found, Map<Category, Integer> counts, Set<Long> seenVeins,
                                 List<Target> xrayOut) {
        Map<Block, Category> wanted = new java.util.HashMap<>();
        scanBlocks.forEach((b, c) -> { if (c.enabled(config)) wanted.put(b, c); });
        if (wanted.isEmpty()) return;
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        double px = client.player.getX(), py = client.player.getY(), pz = client.player.getZ();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            if (Math.abs(baseY + 8 - py) > Math.sqrt(maxSq) + 8) continue;
            if (!section.hasAny(state -> wanted.containsKey(state.getBlock()))) continue;

            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block block = section.getBlockState(x, y, z).getBlock();
                Category cat = wanted.get(block);
                if (cat == null) continue;
                if (disabled.contains(cat == Category.VALUABLE ? VALUABLE_GROUP : cat == Category.WORKSTATION ? WORKSTATION_GROUP : blockKey(block))) continue;
                int wx = baseX + x, wy = baseY + y, wz = baseZ + z;
                double dx = wx + 0.5 - px, dy = wy + 0.5 - py, dz = wz + 0.5 - pz;
                double sq = dx * dx + dy * dy + dz * dz;
                if (sq > maxSq) continue;
                if (xrayOut != null) xrayOut.add(new Target(cat, "", new BlockPos(wx, wy, wz), Math.sqrt(sq)));
                countBlock(label(block, cat == Category.ORE), cat); // every block, not per vein
                // one entry per vein: group ores of the same type in 4x4x4 cells
                long cell = (((long) (wx >> 2) & 0x3FFFFF) << 42) | (((long) (wy >> 2) & 0xFFFFF) << 22)
                    | ((long) (wz >> 2) & 0x3FFFFF);
                if (!seenVeins.add(cell * 31 + System.identityHashCode(block))) continue;
                found.add(new Target(cat, label(block, cat == Category.ORE), new BlockPos(wx, wy, wz), Math.sqrt(sq)));
                counts.merge(cat, 1, Integer::sum);
            }
        }
    }

    /** HUD name from the block id, e.g. RED_SHULKER_BOX, ENCHANTING_TABLE, DIAMOND (ore). Cached. */
    private static String label(Block block, boolean ore) {
        return LABELS.computeIfAbsent(block, b -> {
            String path = Registries.BLOCK.getId(b).getPath();
            if (ore) path = path.replace("deepslate_", "").replace("_ore", "");
            return path.toUpperCase(Locale.ROOT);
        });
    }

    private static final Set<Block> MACHINES = Set.of(Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON);
    private static final Map<Block, String> FARM_PLANTS = Map.of(
        Blocks.KELP, "KELP", Blocks.KELP_PLANT, "KELP", Blocks.SUGAR_CANE, "SUGAR_CANE", Blocks.CACTUS, "CACTUS",
        Blocks.BAMBOO, "BAMBOO", Blocks.PUMPKIN, "PUMPKIN", Blocks.MELON, "MELON");

    /**
     * Finds built farms per 16x16x16 section. Only sections containing observers/pistons are read in full (cheap).
     * Plant farm: 8+ farm plants and 2+ observers/pistons together (natural kelp/cane never has those).
     * Underground farm: below y=0, 4+ observers/pistons together.
     */
    private static void scanFarmBlocks(MinecraftClient client, WorldChunk chunk, double maxSq,
                                       List<Target> found, Map<Category, Integer> counts) {
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (!section.hasAny(st -> MACHINES.contains(st.getBlock()))) continue;
            int baseY = chunk.sectionIndexToCoord(i) << 4;

            int machines = 0;
            Map<String, Integer> plants = new java.util.HashMap<>();
            long mx = 0, my = 0, mz = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block b = section.getBlockState(x, y, z).getBlock();
                if (MACHINES.contains(b)) { machines++; mx += x; my += y; mz += z; continue; }
                String plant = FARM_PLANTS.get(b);
                if (plant != null) plants.merge(plant, 1, Integer::sum);
            }
            if (machines == 0) continue;

            String best = null;
            int bestN = 0;
            for (Map.Entry<String, Integer> e : plants.entrySet()) {
                if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
            }
            String key, label;
            if (best != null && bestN >= 8 && machines >= 2) { key = "plant_farm"; label = best + "_FARM"; }
            else if (baseY < 0 && machines >= 4) { key = "underground_farm"; label = "UNDERGROUND_FARM"; }
            else continue;
            if (disabled.contains(key)) continue;

            BlockPos pos = new BlockPos(baseX + (int) (mx / machines), baseY + (int) (my / machines), baseZ + (int) (mz / machines));
            double sq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (sq > maxSq) continue;
            found.add(new Target(Category.FARM, label + (baseY < 0 && key.equals("plant_farm") ? " (y<0)" : ""), pos, Math.sqrt(sq)));
            counts.merge(Category.FARM, 1, Integer::sum);
            countBlock(label, Category.FARM);
        }
    }

    /** Adds one to the corner-panel count for this block type (only while a scan is running). */
    private static void countBlock(String label, Category cat) {
        ScanJob j = job;
        if (j == null) return;
        j.blockCounts.merge(label, new BlockCount(label, 1, cat.color),
            (a, b) -> new BlockCount(a.label(), a.count() + 1, a.color()));
    }

    /** Nearest built farm from the last scan, or null. */
    static Target nearestFarm() {
        for (Target t : targets) if (t.category() == Category.FARM) return t;
        return null;
    }

    /** Key used by the per-target on/off buttons, e.g. "chest", "hopper", "mob_spawner". */
    private static String beKey(BlockEntityType<?> type) {
        if (type == BlockEntityType.FURNACE || type == BlockEntityType.SMOKER || type == BlockEntityType.BLAST_FURNACE)
            return WORKSTATION_GROUP;
        return BE_KEYS.computeIfAbsent(type, t -> Registries.BLOCK_ENTITY_TYPE.getId(t).getPath());
    }

    /** One on/off key for every block in the "Valuable blocks" group. */
    static final String VALUABLE_GROUP = "valuable_blocks";
    /** One on/off key for anvils, workbenches, furnaces and smokers. */
    static final String WORKSTATION_GROUP = "workstations";

    /** Key for scanned blocks; deepslate variants share the normal one, e.g. "diamond_ore". */
    static String blockKey(Block block) {
        return Registries.BLOCK.getId(block).getPath().replace("deepslate_", "");
    }

    /** Everything the panel lists as its own on/off button: {key, label, category}. */
    static List<Object[]> targetOptions() {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"mob_spawner", "Spawner", Category.SPAWNER});
        out.add(new Object[]{"ender_chest", "Ender chest", Category.VALUABLE});
        out.add(new Object[]{"enchanting_table", "Enchanting table", Category.VALUABLE});
        out.add(new Object[]{"beacon", "Beacon", Category.VALUABLE});
        out.add(new Object[]{VALUABLE_GROUP, "Valuable blocks", Category.VALUABLE});
        out.add(new Object[]{WORKSTATION_GROUP, "Workstations", Category.WORKSTATION});
        Set<String> seen = new HashSet<>();
        out.add(new Object[]{"shulker_box", "Shulker box", Category.SHULKER});
        out.add(new Object[]{"chest", "Chest", Category.STORAGE});
        out.add(new Object[]{"barrel", "Barrel", Category.STORAGE});
        out.add(new Object[]{"hopper", "Hopper", Category.STORAGE});
        out.add(new Object[]{"crafter", "Crafter", Category.STORAGE});
        out.add(new Object[]{"dispenser", "Dispenser", Category.STORAGE});
        out.add(new Object[]{"dropper", "Dropper", Category.STORAGE});
        out.add(new Object[]{"plant_farm", "Plant farms", Category.FARM});
        out.add(new Object[]{"underground_farm", "Farms below y=0", Category.FARM});
        for (Map.Entry<Block, Category> e : scanBlocks.entrySet()) {
            if (e.getValue() != Category.ORE) continue;
            String k = blockKey(e.getKey());
            if (seen.add(k)) out.add(new Object[]{k, pretty(k), Category.ORE});
        }
        return out;
    }

    private static String pretty(String key) {
        String s = key.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isSecondChestHalf(BlockEntity be) {
        BlockState state = be.getCachedState();
        return state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) == ChestType.RIGHT;
    }

    private static Category categoryFor(BlockEntity be) {
        BlockEntityType<?> t = be.getType();
        if (t == BlockEntityType.MOB_SPAWNER)
            return Category.SPAWNER;
        if (t == BlockEntityType.ENDER_CHEST || t == BlockEntityType.ENCHANTING_TABLE || t == BlockEntityType.BEACON)
            return Category.VALUABLE;
        if (t == BlockEntityType.FURNACE || t == BlockEntityType.SMOKER || t == BlockEntityType.BLAST_FURNACE)
            return Category.WORKSTATION;
        if (t == BlockEntityType.SHULKER_BOX)
            return Category.SHULKER;
        if (t == BlockEntityType.CHEST || t == BlockEntityType.BARREL
            || t == BlockEntityType.HOPPER || t == BlockEntityType.CRAFTER
            || t == BlockEntityType.DISPENSER || t == BlockEntityType.DROPPER)
            return Category.STORAGE;
        return null;
    }

    enum Category {
        ORE(0xFF55FFFF, "O"),        // cyan
        SPAWNER(0xFFB455FF, "Sp"),   // purple
        VALUABLE(0xFF4488FF, "V"),   // blue: enchanting tables, ender chests, beacons, expensive blocks
        SHULKER(0xFF55DD55, "Sh"),   // green
        WORKSTATION(0xFF1F8A2B, "W"), // dark green: anvils, workbenches, furnaces, smokers
        STORAGE(0xFFFF4444, "St"),   // red: chests, barrels, hoppers, crafters, dispensers, droppers
        FARM(0xFFFF8800, "F");       // orange: built kelp/cane/cactus/etc. farms and farms below y=0

        final int color;
        final String code;

        Category(int color, String code) { this.color = color; this.code = code; }

        boolean enabled(MegaConfig c) {
            return switch (this) {
                case ORE -> c.ores;
                case SPAWNER -> c.spawners;
                case VALUABLE -> c.valuables;
                case SHULKER -> c.shulkers;
                case STORAGE -> c.storage;
                case WORKSTATION -> c.workstations;
                case FARM -> c.farmAlert;
            };
        }
    }

    record Target(Category category, String label, BlockPos pos, double distance) {}

    record PlayerInfo(String name, double x, double z, double distance, boolean visible) {}
}
