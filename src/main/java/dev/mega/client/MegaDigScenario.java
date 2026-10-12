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
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.nio.file.Path;
import java.util.Locale;

/**
 * "Dig to the stash" footage, only runs with -Dmega.scenario=dig.
 * Normal world, a stash buried ~100 blocks under the surface. Hover above it, find it with M.E.G.A (tracers, then X-ray),
 * dig a shaft straight down, break into the room and place a banner. CI turns the frames into a video.
 */
@SuppressWarnings("UnstableApiUsage")
public class MegaDigScenario implements FabricClientGameTest {
    private int frame;
    private Path out;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!"dig".equals(System.getProperty("mega.scenario"))) return;
        out = FabricLoader.getInstance().getGameDir().resolve("scenario_frames");

        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> s.setSeed("megadig"))
                .create()) {
            TestServerContext srv = sp.getServer();
            for (String c : new String[]{"gamemode creative @a", "time set noon", "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false", "weather clear", "gamerule doMobSpawning false", "gamerule doTileDrops false", "gamerule announceAdvancements false", "gamerule sendCommandFeedback false",
                "effect give @a minecraft:night_vision infinite 0 true",
                "item replace entity @a hotbar.0 with minecraft:netherite_pickaxe",
                "item replace entity @a hotbar.1 with minecraft:purple_banner"}) srv.runCommand(c);
            ctx.runOnClient(c -> { c.options.getViewDistance().setValue(6); c.options.getMaxFps().setValue(20); });

            // Bury the stash ~100 blocks under the surface, 40 blocks from spawn
            int[] info = srv.computeOnServer(server -> buildStash(server.getOverworld()));
            int x = info[0], z = info[1], top = info[2], floor = info[3];
            MegaClient.LOGGER.info("[M.E.G.A dig] stash at {} {} {} (surface {}, {} blocks down)", x, floor, z, top, top - floor);

            // 1. Hover above it: M.E.G.A finds it through 100 blocks of rock
            tp(srv, x + 0.5, top + 14, z - 9.5, 0, 55);
            waitChunks(sp);
            ctx.waitTicks(60);
            ctx.runOnClient(c -> c.player.getInventory().setSelectedSlot(0));
            for (int f = 0; f < 36; f++) {
                tp(srv, x + 0.5, top + 14 - f * 0.1, z - 9.5 + f * 0.2, 0, 55 + f * 0.8);
                shot(ctx);
            }
            // 2. X-ray on: the stash shows through the ground
            ctx.runOnClient(c -> { MegaClient.config.xray = true; MegaClient.applyConfig(); });
            ctx.waitTicks(60);
            for (int f = 0; f < 30; f++) {
                tp(srv, x + 0.5, top + 10.4 - f * 0.1, z - 2.3 + f * 0.05, 0, 84);
                shot(ctx);
            }
            ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
            ctx.waitTicks(40);

            // 3. Dig straight down: break the block under your feet each step and drop one block
            int ceiling = floor + 4; // room is 4 tall
            double px = x + 0.5, pz = z - 0.5;
            int bx = x, bz = z - 1; // dig just beside the middle so you land next to the spawner
            for (int y = top; y > ceiling; y--) {
                srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air destroy", bx, y, bz));
                srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air destroy", bx, y + 1, bz));
                tp(srv, px, y + 0.0, pz, 0, 88);
                ctx.runOnClient(c -> c.player.swingHand(Hand.MAIN_HAND));
                ctx.waitTicks(1);
                if ((top - y) % 2 == 0) shot(ctx); // every other block, so the dig isn't too slow to watch
            }
            // 4. Break into the room and drop in
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air destroy", bx, ceiling, bz));
            for (int f = 0; f < 10; f++) {
                tp(srv, px, ceiling - f * 0.35, pz, 0, 88 - f * 6);
                ctx.runOnClient(c -> c.player.swingHand(Hand.MAIN_HAND));
                shot(ctx);
            }
            // 5. Look round the stash
            for (int f = 0; f < 36; f++) {
                float yaw = f * 10f;
                tp(srv, px, floor + 1, pz, yaw, 18);
                shot(ctx);
            }
            // 6. Place the banner
            ctx.runOnClient(c -> c.player.getInventory().setSelectedSlot(1));
            tp(srv, px, floor + 1, pz, 0, 35);
            ctx.waitTicks(2);
            shot(ctx);
            ctx.runOnClient(c -> c.player.swingHand(Hand.MAIN_HAND));
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:purple_banner[rotation=8]", x + 1, floor, z + 1));
            for (int f = 0; f < 24; f++) {
                tp(srv, px, floor + 1, pz, 0, 35 - f * 0.6f);
                shot(ctx);
            }
            MegaClient.LOGGER.info("[M.E.G.A dig] DONE, {} frames", frame);
        }
    }

    private void shot(ClientGameTestContext ctx) {
        ctx.waitTick();
        ctx.runOnClient(c -> { c.getToastManager().clear(); c.inGameHud.getChatHud().clear(false); });
        ctx.takeScreenshot(TestScreenshotOptions.of(String.format("f%04d", frame++)).disableCounterPrefix().withDestinationDir(out));
    }

    private static void waitChunks(TestSingleplayerContext sp) {
        try {
            sp.getClientWorld().waitForChunksRender(true, 20 * 90);
        } catch (AssertionError e) {
            MegaClient.LOGGER.warn("[M.E.G.A dig] some chunks still loading, carrying on");
        }
    }

    private static void tp(TestServerContext srv, double x, double y, double z, double yaw, double pitch) {
        srv.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
    }

    /** Returns {x, z, surfaceY, floorY}. */
    private static int[] buildStash(ServerWorld w) {
        int x = 40, z = 40;
        for (int cx = -5; cx <= 5; cx++) for (int cz = -5; cz <= 5; cz++) w.getChunk((x >> 4) + cx, (z >> 4) + cz);
        int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z - 1) - 1;
        int floor = Math.max(-58, top - 100);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy < 4; dy++)
            w.setBlockState(new BlockPos(x + dx, floor + dy, z + dz), Blocks.AIR.getDefaultState());
        Block[] back = {Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.CHEST, Blocks.BARREL, Blocks.CHEST, Blocks.HOPPER};
        for (int dx = -3; dx <= 3; dx++) w.setBlockState(new BlockPos(x + dx, floor, z + 3), back[dx + 3].getDefaultState());
        w.setBlockState(new BlockPos(x - 2, floor, z - 3), Blocks.PURPLE_SHULKER_BOX.getDefaultState());
        w.setBlockState(new BlockPos(x, floor, z - 3), Blocks.LIME_SHULKER_BOX.getDefaultState());
        w.setBlockState(new BlockPos(x + 2, floor, z - 3), Blocks.BLACK_SHULKER_BOX.getDefaultState());
        w.setBlockState(new BlockPos(x - 3, floor, z - 1), Blocks.DIAMOND_BLOCK.getDefaultState());
        w.setBlockState(new BlockPos(x - 3, floor, z + 1), Blocks.NETHERITE_BLOCK.getDefaultState());
        w.setBlockState(new BlockPos(x - 3, floor + 1, z), Blocks.GOLD_BLOCK.getDefaultState());
        w.setBlockState(new BlockPos(x + 3, floor, z - 1), Blocks.ENDER_CHEST.getDefaultState());
        w.setBlockState(new BlockPos(x + 3, floor, z + 1), Blocks.ANVIL.getDefaultState());
        w.setBlockState(new BlockPos(x + 3, floor + 1, z), Blocks.ENCHANTING_TABLE.getDefaultState());
        w.setBlockState(new BlockPos(x, floor, z), Blocks.SPAWNER.getDefaultState());
        for (int dx = -3; dx <= 3; dx += 6) for (int dz = -3; dz <= 3; dz += 6)
            w.setBlockState(new BlockPos(x + dx, floor + 3, z + dz), Blocks.GLOWSTONE.getDefaultState());
        return new int[]{x, z, top, floor};
    }
}
