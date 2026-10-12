package dev.mega.client;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import java.util.HashSet;
import java.util.Set;

/**
 * M.E.G.A X-ray: while on, the block mixin only lets X-ray blocks draw their faces, so every other block is invisible
 * and X-ray blocks keep their real textures. Client-only night vision keeps buried blocks bright.
 * Coloured edges round X-ray blocks are drawn separately in MegaTracers.
 */
public final class MegaXray {
    /** Read from chunk-building threads, so volatile + immutable set. */
    private static volatile boolean active;
    private static volatile Set<Block> visible = Set.of();
    private static boolean addedNightVision;

    private MegaXray() {}

    public static boolean active() { return active; }

    /** Called by the block mixin for every face that could be culled. */
    public static boolean isVisible(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ShulkerBoxBlock || visible.contains(b);
    }

    /** Applies the config value; rebuilds chunks only if something changed. */
    static void apply(MinecraftClient client, Set<Block> scanned) {
        Set<Block> set = new HashSet<>(scanned); // ores + valuable blocks
        set.add(Blocks.SPAWNER);
        set.add(Blocks.CHEST);
        set.add(Blocks.BARREL);
        set.add(Blocks.ENDER_CHEST);
        set.add(Blocks.HOPPER);
        set.add(Blocks.CRAFTER);
        set.add(Blocks.DISPENSER);
        set.add(Blocks.DROPPER);
        set.add(Blocks.ENCHANTING_TABLE);
        set.add(Blocks.BEACON);
        set.add(Blocks.FURNACE);
        set.add(Blocks.SMOKER);
        set.add(Blocks.BLAST_FURNACE);
        boolean want = MegaClient.config.xray;
        boolean changed = want != active || (want && !set.equals(visible));
        visible = Set.copyOf(set);
        active = want;
        if (changed && client.worldRenderer != null) client.worldRenderer.reload();
        if (!want) removeNightVision(client);
    }

    /** Ghost outlines: hidden blocks next to air (cave walls, surfaces) near you, drawn as edges in the block's colour. */
    static long[] ghostPos = new long[0];
    static int[] ghostColor = new int[0];
    static int ghostCount;
    private static int ghostTimer;
    private static final Direction[] DIRS = Direction.values();

    /** Rescans ghost outlines every 10 ticks within config.xrayGhostRange (cube around you). */
    static void scanGhosts(MinecraftClient client) {
        MegaConfig cfg = MegaClient.config;
        if (!active || !cfg.xrayGhost || client.world == null || client.player == null) { ghostCount = 0; return; }
        if (++ghostTimer < 10) return;
        ghostTimer = 0;
        World world = client.world;
        int r = cfg.xrayGhostRange, max = cfg.xrayGhostMax;
        BlockPos c = client.player.getBlockPos();
        long[] pos = new long[max];
        int[] col = new int[max];
        int n = 0;
        BlockPos.Mutable m = new BlockPos.Mutable(), nb = new BlockPos.Mutable();
        outer:
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
            BlockState st = world.getBlockState(m);
            if (st.isAir() || !st.isOpaqueFullCube() || isVisible(st)) continue;
            boolean exposed = false;
            for (Direction d : DIRS) {
                nb.set(m.getX() + d.getOffsetX(), m.getY() + d.getOffsetY(), m.getZ() + d.getOffsetZ());
                if (!world.getBlockState(nb).isOpaqueFullCube()) { exposed = true; break; }
            }
            if (!exposed) continue;
            pos[n] = m.asLong();
            col[n] = 0xFF000000 | st.getMapColor(world, m).color;
            if (++n >= max) break outer;
        }
        ghostPos = pos;
        ghostColor = col;
        ghostCount = n;
    }

    /** Keeps the client-side night vision on while x-ray is active (every tick, cheap). */
    static void tick(MinecraftClient client) {
        if (client.player == null) { addedNightVision = false; return; }
        if (active && !client.player.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
            client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION,
                StatusEffectInstance.INFINITE, 0, false, false, false));
            addedNightVision = true;
        }
    }

    private static void removeNightVision(MinecraftClient client) {
        if (addedNightVision && client.player != null) client.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        addedNightVision = false;
    }
}
