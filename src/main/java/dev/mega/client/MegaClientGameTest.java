package dev.mega.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * In-game test, run on GitHub Actions with the real 1.21.11 client (only when -Dfabric.client.gametest is set;
 * normal players never load this class). Builds a scene, checks the mod finds it, toggles X-ray, opens the panels
 * and takes screenshots.
 */
@SuppressWarnings("UnstableApiUsage")
public class MegaClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            TestServerContext srv = sp.getServer();
            // Flat test world: grass top at y=-61.
            srv.runCommand("time set noon");
            srv.runCommand("weather clear");
            srv.runCommand("gamerule doDaylightCycle false");
            // Stone hill ahead with a hidden stash inside
            srv.runCommand("fill 3 -60 6 11 -55 14 minecraft:stone");
            srv.runCommand("setblock 5 -59 8 minecraft:diamond_ore");
            srv.runCommand("setblock 6 -59 8 minecraft:deepslate_diamond_ore");
            srv.runCommand("setblock 7 -59 9 minecraft:gold_ore");
            srv.runCommand("setblock 6 -58 10 minecraft:spawner");
            srv.runCommand("setblock 8 -59 10 minecraft:chest");
            srv.runCommand("setblock 9 -59 10 minecraft:hopper");
            srv.runCommand("setblock 8 -58 11 minecraft:lime_shulker_box");
            srv.runCommand("setblock 9 -58 11 minecraft:ender_chest");
            srv.runCommand("setblock 7 -57 12 minecraft:diamond_block");
            srv.runCommand("setblock 8 -57 12 minecraft:netherite_block");
            srv.runCommand("setblock 9 -57 12 minecraft:quartz_pillar");
            // Lava pocket inside rock, left side, and a small water pool
            srv.runCommand("fill -9 -62 6 -3 -58 12 minecraft:stone");
            srv.runCommand("fill -7 -61 8 -5 -60 10 minecraft:lava");
            srv.runCommand("fill -3 -61 1 -1 -61 3 minecraft:water");
            // Stand back and look at it
            srv.runCommand("tp @a 0 -60 -3 0 15");
            sp.getClientWorld().waitForChunksRender();
            ctx.waitTicks(100); // a few full scans

            int targets = ctx.computeOnClient(c -> MegaClient.targets.size());
            Set<MegaClient.Category> cats = ctx.computeOnClient(c -> {
                Set<MegaClient.Category> s = EnumSet.noneOf(MegaClient.Category.class);
                for (MegaClient.Target t : MegaClient.targets) s.add(t.category());
                return s;
            });
            int kinds = ctx.computeOnClient(c -> MegaClient.blockCounts.size());
            MegaClient.LOGGER.info("[M.E.G.A test] targets={} categories={} blockTypes={}", targets, cats, kinds);
            check(targets >= 8, "expected at least 8 targets, found " + targets);
            for (MegaClient.Category c : new MegaClient.Category[]{MegaClient.Category.ORE, MegaClient.Category.SPAWNER,
                MegaClient.Category.STORAGE, MegaClient.Category.SHULKER, MegaClient.Category.VALUABLE}) {
                check(cats.contains(c), "category not found: " + c);
            }
            check(kinds >= 8, "Blocks seen panel should list at least 8 block types, has " + kinds);
            ctx.takeScreenshot("01-esp-normal");

            // M.E.G.A X-ray on
            ctx.runOnClient(c -> { MegaClient.config.xray = true; MegaClient.applyConfig(); });
            sp.getClientWorld().waitForChunksRender();
            ctx.waitTicks(40);
            boolean xr = ctx.computeOnClient(c -> MegaXray.active());
            int ghosts = ctx.computeOnClient(c -> MegaXray.ghostCount);
            int lava = ctx.computeOnClient(c -> MegaXray.lavaCount);
            MegaClient.LOGGER.info("[M.E.G.A test] xray={} ghostOutlines={} lavaFaces={}", xr, ghosts, lava);
            check(xr, "X-ray did not turn on");
            check(ghosts > 0, "X-ray outlines missing");
            check(lava > 0, "see-through lava missing");
            ctx.takeScreenshot("02-xray");
            ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
            sp.getClientWorld().waitForChunksRender();
            ctx.waitTicks(10);
            check(!ctx.computeOnClient(c -> MegaXray.active()), "X-ray did not turn off");

            // Settings panels
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("03-panel");
            ctx.setScreen(() -> new MegaTargetsScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("04-what-to-detect");
            ctx.setScreen(() -> null);

            // Farm alert: lots of moving dropped items
            for (int i = 0; i < 40; i++) {
                srv.runCommand("summon minecraft:item " + (i % 8 - 4) + " -55 4 {Item:{id:\"minecraft:diamond\",count:1},Motion:[0.0,0.5,0.0],PickupDelay:32767}");
            }
            ctx.waitTicks(30);
            int farm = ctx.computeOnClient(c -> MegaClient.farmCount);
            MegaClient.LOGGER.info("[M.E.G.A test] movingItems={}", farm);
            ctx.takeScreenshot("05-farm-alert");
            MegaClient.LOGGER.info("[M.E.G.A test] ALL CHECKS PASSED");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError("[M.E.G.A test] " + message);
    }
}
