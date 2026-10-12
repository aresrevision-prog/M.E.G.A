package dev.mega.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Live footage" scenario, only runs with -Dmega.scenario=true (a separate CI job).
 * Normal (non-flat) world, 8 stashes buried at random spots within 5000 blocks of spawn, then an elytra-style
 * flight to each one, recording frames with X-ray off and then on. CI turns the frames into a video.
 */
@SuppressWarnings("UnstableApiUsage")
public class MegaScenarioTest implements FabricClientGameTest {
    private static final int STASHES = 8, RADIUS = 5000, FRAMES = 64;

    record Stash(BlockPos pos, int top) {}

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("mega.scenario")) return;
        Path out = FabricLoader.getInstance().getGameDir().resolve("scenario_frames");

        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> s.setSeed("megastash"))
                .create()) {
            TestServerContext srv = sp.getServer();
            srv.runCommand("gamemode creative @a");
            srv.runCommand("time set noon");
            srv.runCommand("gamerule doDaylightCycle false");
            srv.runCommand("gamerule doWeatherCycle false");
            srv.runCommand("weather clear");
            srv.runCommand("gamerule doMobSpawning false");
            srv.runCommand("item replace entity @a armor.chest with minecraft:elytra");
            srv.runCommand("item replace entity @a hotbar.0 with minecraft:firework_rocket 64");
            ctx.runOnClient(c -> c.options.getViewDistance().setValue(8));

            List<Stash> stashes = srv.computeOnServer(server -> buildStashes(server.getOverworld()));
            MegaClient.LOGGER.info("[M.E.G.A scenario] stashes: {}", stashes);

            Random r = new Random(77);
            int frame = 0;
            for (int i = 0; i < stashes.size(); i++) {
                Stash s = stashes.get(i);
                double ang = r.nextDouble() * Math.PI * 2;
                double sx = s.pos().getX() - Math.cos(ang) * 70, sz = s.pos().getZ() - Math.sin(ang) * 70;
                double ex = s.pos().getX() - Math.cos(ang) * 8, ez = s.pos().getZ() - Math.sin(ang) * 8;
                double sy = s.top() + 34, ey = s.top() + 16;
                tp(srv, sx, sy, sz, s.pos());
                sp.getClientWorld().waitForChunksRender();
                ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
                ctx.waitTicks(30); // let the mod scan the new area

                for (int f = 0; f < FRAMES; f++) {
                    if (f == FRAMES / 2) { // second half of each pass: X-ray on
                        ctx.runOnClient(c -> { MegaClient.config.xray = true; MegaClient.applyConfig(); });
                        sp.getClientWorld().waitForChunksRender();
                        ctx.waitTicks(12);
                    }
                    double t = f / (double) (FRAMES - 1);
                    tp(srv, sx + (ex - sx) * t, sy + (ey - sy) * t, sz + (ez - sz) * t, s.pos());
                    ctx.waitTicks(1);
                    ctx.takeScreenshot(TestScreenshotOptions.of(String.format("f%04d", frame++))
                        .disableCounterPrefix().withDestinationDir(out));
                }
                int found = ctx.computeOnClient(c -> MegaClient.blockCounts.stream().mapToInt(MegaClient.BlockCount::count).sum());
                MegaClient.LOGGER.info("[M.E.G.A scenario] stash {} at {} -> blocks seen {}", i + 1, s.pos(), found);
            }
            ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
            MegaClient.LOGGER.info("[M.E.G.A scenario] DONE, {} frames", frame);
        }
    }

    /** Teleport the player and face the stash. */
    private static void tp(TestServerContext srv, double x, double y, double z, BlockPos target) {
        double dx = target.getX() + 0.5 - x, dy = target.getY() + 0.5 - y, dz = target.getZ() + 0.5 - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        srv.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
    }

    private static List<Stash> buildStashes(ServerWorld w) {
        Random r = new Random(5000);
        Block[] storage = {Blocks.CHEST, Blocks.BARREL, Blocks.HOPPER};
        Block[] shulkers = {Blocks.PURPLE_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX, Blocks.RED_SHULKER_BOX};
        Block[] valuable = {Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.ENDER_CHEST, Blocks.BEACON};
        Block[] work = {Blocks.ANVIL, Blocks.FURNACE, Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE};
        List<Stash> out = new ArrayList<>();
        for (int i = 0; i < STASHES; i++) {
            int x = r.nextInt(RADIUS * 2 + 1) - RADIUS, z = r.nextInt(RADIUS * 2 + 1) - RADIUS;
            w.getChunk(x >> 4, z >> 4); // generate the terrain there
            int top = w.getTopY(Heightmap.Type.OCEAN_FLOOR, x, z);
            int floor = Math.max(-50, top - 14);
            // carve a 7x4x7 room
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy < 4; dy++)
                w.setBlockState(new BlockPos(x + dx, floor + dy, z + dz), Blocks.AIR.getDefaultState());
            // fill it: walls of storage, a row of shulkers, valuables, workstations, a spawner
            for (int dx = -3; dx <= 3; dx++) w.setBlockState(new BlockPos(x + dx, floor, z + 3), storage[r.nextInt(storage.length)].getDefaultState());
            for (int dx = -2; dx <= 2; dx += 2) w.setBlockState(new BlockPos(x + dx, floor, z - 3), shulkers[r.nextInt(shulkers.length)].getDefaultState());
            for (int k = 0; k < 4; k++) w.setBlockState(new BlockPos(x - 3, floor + (k / 2), z - 1 + (k % 2) * 2), valuable[r.nextInt(valuable.length)].getDefaultState());
            for (int k = 0; k < 2; k++) w.setBlockState(new BlockPos(x + 3, floor, z - 1 + k * 2), work[r.nextInt(work.length)].getDefaultState());
            w.setBlockState(new BlockPos(x, floor, z), Blocks.SPAWNER.getDefaultState());
            // some stashes have a lava pocket next to them
            if (r.nextBoolean()) {
                for (int dx = 6; dx <= 8; dx++) for (int dz = -1; dz <= 1; dz++)
                    w.setBlockState(new BlockPos(x + dx, floor + 1, z + dz), Blocks.LAVA.getDefaultState());
            }
            out.add(new Stash(new BlockPos(x, floor, z), top));
        }
        return out;
    }
}
