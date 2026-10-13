package dev.mega.client;

import net.fabricmc.api.ClientModInitializer;
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
    /** Blocks with a block entity (chests, spawners, shulkers...): found from the chunk's block-entity list (fast). */
    private static Map<Block, Category> beBlocks = Map.of();
    /** Farm plants -> label, e.g. KELP. */
    private static Map<Block, String> farmPlants = Map.of();
    private static Set<String> ignored = Set.of();
    private static Set<net.minecraft.item.Item> ignoredItems = Set.of();
    private static Set<String> disabled = Set.of();
    private static final Map<Block, String> LABELS = new java.util.HashMap<>();

    private static KeyBinding reloadKey;
    private static KeyBinding settingsKey;
    private static KeyBinding waypointKey;
    private static KeyBinding deepKey;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        loadConfig();
        version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");

        // No Fabric API needed: key bindings, the tick, the HUD and world drawing are hooked with our own mixins
        // (mixin/GameOptionsMixin, MinecraftClientMixin, InGameHudMixin, WorldRendererMixin).
        // Names are plain text because without Fabric API the game doesn't load mod language files.
        settingsKey = new KeyBinding("M.E.G.A settings panel", GLFW.GLFW_KEY_0, KeyBinding.Category.MISC);
        reloadKey = new KeyBinding("M.E.G.A refresh", GLFW.GLFW_KEY_R, KeyBinding.Category.MISC);
        waypointKey = new KeyBinding("M.E.G.A add waypoint here", GLFW.GLFW_KEY_B, KeyBinding.Category.MISC);
        // T is chat in Minecraft, so deep scan defaults to G (change it in Controls)
        deepKey = new KeyBinding("M.E.G.A deep scan (underground only)", GLFW.GLFW_KEY_G, KeyBinding.Category.MISC);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) mc.options.allKeys = addKeys(mc.options.allKeys); // options already made

        LOGGER.info("M.E.G.A Client {} loaded", version);
    }

    /** Adds our key bindings to the game's list (called from GameOptionsMixin before options load). */
    public static KeyBinding[] addKeys(KeyBinding[] keys) {
        if (settingsKey == null) return keys;
        for (KeyBinding k : keys) if (k == settingsKey) return keys;
        KeyBinding[] out = java.util.Arrays.copyOf(keys, keys.length + 4);
        out[keys.length] = settingsKey;
        out[keys.length + 1] = reloadKey;
        out[keys.length + 2] = waypointKey;
        out[keys.length + 3] = deepKey;
        return out;
    }

    public static void onTick(MinecraftClient client) {
        while (settingsKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new MegaScreen(null));
        }
        while (reloadKey.wasPressed()) refresh(client);
        while (deepKey.wasPressed()) {
            config.deepScan = !config.deepScan;
            config.save();
            refresh(client);
            message(client, config.deepScan ? "Deep scan ON: only stashes buried underground" : "Deep scan OFF: everything",
                config.deepScan ? Formatting.GOLD : Formatting.AQUA);
        }
        while (waypointKey.wasPressed()) {
            if (client.player != null && client.world != null) {
                MegaWaypoints.Waypoint w = MegaWaypoints.addHere(client, null);
                message(client, "Added " + w.name + " at " + w.x + ", " + w.y + ", " + w.z + " (press 0 > Waypoints to rename)", Formatting.GREEN);
            }
        }
        MegaWaypoints.tick(client);

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

    /** R: reload the config file, forget old results and scan everything again right now. */
    static void refresh(MinecraftClient client) {
        loadConfig();
        job = null;
        targets = List.of();
        xrayBlocks = List.of();
        blockCounts = List.of();
        counts.clear();
        lastItemPos.clear();
        tickCounter = Integer.MAX_VALUE - 1; // full rescan on this tick
        if (MegaXray.active() && client.worldRenderer != null) client.worldRenderer.reload();
        message(client, "Refreshed", Formatting.AQUA);
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
        // Every colour's block list. A block in two lists goes to the later one.
        Map<Block, Category> all = new java.util.LinkedHashMap<>();
        for (Category c : Category.values()) if (c != Category.FARM) addBlocks(all, c.blocks(config), c);
        Map<Block, Category> be = new java.util.HashMap<>(), plain = new java.util.LinkedHashMap<>();
        all.forEach((b, c) -> {
            if (disabled.contains(Registries.BLOCK.getId(b).toString())) return; // switched off on its own
            (b instanceof net.minecraft.block.BlockEntityProvider ? be : plain).put(b, c);
        });
        beBlocks = be;
        scanBlocks = plain;
        Map<Block, String> plants = new java.util.HashMap<>();
        Map<Block, Category> tmp = new java.util.HashMap<>();
        addBlocks(tmp, config.farmPlants, Category.FARM);
        for (Block b : tmp.keySet()) {
            String id = Registries.BLOCK.getId(b).toString();
            if (!disabled.contains(id)) plants.put(b, Registries.BLOCK.getId(b).getPath().replace("_plant", "").toUpperCase(Locale.ROOT));
        }
        farmPlants = plants;
        MegaXray.apply(MinecraftClient.getInstance(), all.keySet());
    }

    private static void addBlocks(Map<Block, Category> map, List<String> ids, Category cat) {
        for (String id : ids) {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) { LOGGER.warn("Bad block id: {}", id); continue; }
            Registries.BLOCK.getOptionalValue(ident).ifPresentOrElse(b -> map.put(b, cat),
                () -> LOGGER.warn("Unknown block: {}", id));
        }
    }

    static void message(MinecraftClient client, String msg, Formatting color) {
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
        j.scanOres = !scanBlocks.isEmpty();
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
                Category category = beBlocks.get(be.getCachedState().getBlock());
                if (category == null || !category.enabled(config)) continue;
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
            if (config.deepScan) j.found.removeIf(t -> !underground(client, t.pos()));
            targets = everyColourFirst(j.found);
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

            // A farm machine points AT a plant: an observer watching it, or a piston facing it (1-2 blocks along
            // its facing, or just above/below that, for cane/cactus/bamboo columns). Random pistons near wild plants
            // (jungle temples, villages) don't point at plants, so they no longer count.
            int machines = 0, aimed = 0;
            Map<String, Integer> plants = new java.util.HashMap<>();
            long mx = 0, my = 0, mz = 0;
            BlockPos.Mutable q = new BlockPos.Mutable();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = section.getBlockState(x, y, z);
                if (!MACHINES.contains(st.getBlock())) continue;
                machines++; mx += x; my += y; mz += z;
                net.minecraft.util.math.Direction f = st.getOrEmpty(net.minecraft.state.property.Properties.FACING).orElse(null);
                if (f == null) continue;
                int wx = baseX + x, wy = baseY + y, wz = baseZ + z;
                String hit = null;
                search:
                for (int dist = 1; dist <= 2; dist++) for (int dy = -1; dy <= 1; dy++) {
                    q.set(wx + f.getOffsetX() * dist, wy + f.getOffsetY() * dist + dy, wz + f.getOffsetZ() * dist);
                    String plant = farmPlants.get(client.world.getBlockState(q).getBlock());
                    if (plant != null) { hit = plant; break search; }
                }
                if (hit != null) { aimed++; plants.merge(hit, 1, Integer::sum); }
            }
            if (machines == 0) continue;

            String best = null;
            int bestN = 0;
            for (Map.Entry<String, Integer> e : plants.entrySet()) {
                if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); } // the plant most machines aim at
            }
            String key, label;
            if (best != null && aimed >= 2) { key = "plant_farm"; label = best + "_FARM"; }
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

    /**
     * The nearest target of every colour that's on goes first (nearest first), then the rest in the usual order.
     * So the list and the lines always show every colour that was found, even when one colour (e.g. chests) is
     * much closer or more common than the others.
     */
    /** Buried: at least 4 blocks under the surface (top solid block) above it. */
    static boolean underground(MinecraftClient client, BlockPos p) {
        int top = client.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() <= top - 5;
    }

    static List<Target> everyColourFirst(List<Target> sorted) {
        List<Target> first = new ArrayList<>(), rest = new ArrayList<>();
        Set<Category> seen = java.util.EnumSet.noneOf(Category.class);
        for (Target t : sorted) (seen.add(t.category()) ? first : rest).add(t);
        first.sort(Comparator.comparingDouble(Target::distance));
        first.addAll(rest);
        return first;
    }

    /** Text colour that stays readable on the dark HUD panel (dark colours like dark green are lightened). */
    static int readable(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (lum >= 0.5) return argb | 0xFF000000;
        double k = (0.5 - lum) / (1 - lum) + 0.1; // mix towards white just enough
        r += (int) ((255 - r) * k); g += (int) ((255 - g) * k); b += (int) ((255 - b) * k);
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    private static boolean isSecondChestHalf(BlockEntity be) {
        BlockState state = be.getCachedState();
        return state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) == ChestType.RIGHT;
    }

    enum Category {
        ORE(0xFF55FFFF, "O", "Ores", "cyan"),
        SPAWNER(0xFFB455FF, "Sp", "Spawners", "purple"),
        VALUABLE(0xFF4488FF, "V", "Valuables", "blue"),
        SHULKER(0xFF55DD55, "Sh", "Shulkers", "green"),
        WORKSTATION(0xFF1F8A2B, "W", "Workstations", "dark green"),
        STORAGE(0xFFFF4444, "St", "Storage", "red"),
        FARM(0xFFFF8800, "F", "Farms", "orange"); // built plant farms (plants next to observers/pistons) + machines below y=0

        final int color;
        final String code, title, colourName;

        Category(int color, String code, String title, String colourName) {
            this.color = color; this.code = code; this.title = title; this.colourName = colourName;
        }

        /** This colour's editable block list (for Farms: the plants that make a farm). */
        List<String> blocks(MegaConfig c) {
            return switch (this) {
                case ORE -> c.oreBlocks;
                case SPAWNER -> c.spawnerBlocks;
                case VALUABLE -> c.valuableBlocks;
                case SHULKER -> c.shulkerBlocks;
                case STORAGE -> c.storageBlocks;
                case WORKSTATION -> c.workstationBlocks;
                case FARM -> c.farmPlants;
            };
        }

        List<String> defaults() {
            return switch (this) {
                case ORE -> MegaConfig.DEFAULT_ORES;
                case SPAWNER -> MegaConfig.DEFAULT_SPAWNERS;
                case VALUABLE -> MegaConfig.DEFAULT_VALUABLES;
                case SHULKER -> MegaConfig.DEFAULT_SHULKERS;
                case STORAGE -> MegaConfig.DEFAULT_STORAGE;
                case WORKSTATION -> MegaConfig.DEFAULT_WORKSTATIONS;
                case FARM -> MegaConfig.DEFAULT_FARM_PLANTS;
            };
        }

        void setEnabled(MegaConfig c, boolean on) {
            switch (this) {
                case ORE -> c.ores = on;
                case SPAWNER -> c.spawners = on;
                case VALUABLE -> c.valuables = on;
                case SHULKER -> c.shulkers = on;
                case STORAGE -> c.storage = on;
                case WORKSTATION -> c.workstations = on;
                case FARM -> c.farmAlert = on;
            }
        }

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
