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
        if (System.getProperty("mega.scenario") != null) return; // the scenario job runs MegaScenarioTest only
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
            srv.runCommand("setblock 5 -57 12 minecraft:crafting_table");
            srv.runCommand("setblock 4 -57 12 minecraft:furnace");
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
                MegaClient.Category.STORAGE, MegaClient.Category.SHULKER, MegaClient.Category.VALUABLE, MegaClient.Category.WORKSTATION}) {
                check(cats.contains(c), "category not found: " + c);
            }
            int firstN = ctx.computeOnClient(c -> MegaClient.config.maxTracers);
            Set<MegaClient.Category> lined = ctx.computeOnClient(c -> {
                Set<MegaClient.Category> s = EnumSet.noneOf(MegaClient.Category.class);
                for (int i = 0; i < Math.min(firstN, MegaClient.targets.size()); i++) s.add(MegaClient.targets.get(i).category());
                return s;
            });
            MegaClient.LOGGER.info("[M.E.G.A test] colours with a line: {}", lined);
            check(lined.containsAll(cats), "every colour found should get a line, missing: " + cats.stream().filter(x -> !lined.contains(x)).toList());
            // natural ores (any colour) come after placed stash blocks, in the list and the Blocks seen panel
            String firstLabel = ctx.computeOnClient(c -> MegaClient.targets.get(0).label() + "/" + MegaClient.blockCounts.get(0).label());
            MegaClient.LOGGER.info("[M.E.G.A test] first row / first block type: {}", firstLabel);
            check(!firstLabel.contains("DIAMOND/") && !firstLabel.startsWith("GOLD") && !firstLabel.endsWith("/DIAMOND") && !firstLabel.endsWith("/GOLD"),
                "natural ore listed before stash blocks: " + firstLabel);
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
            check(ctx.computeOnClient(c -> MegaXray.isVisible(net.minecraft.block.Blocks.BEDROCK.getDefaultState())), "bedrock hidden in X-ray");
            ctx.takeScreenshot("02-xray");
            ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
            sp.getClientWorld().waitForChunksRender();
            ctx.waitTicks(10);
            check(!ctx.computeOnClient(c -> MegaXray.active()), "X-ray did not turn off");

            // Settings panels
            ctx.runOnClient(c -> MegaScreen.tab = 0);
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("03-panel");
            ctx.setScreen(() -> new MegaBlocksScreen(new MegaScreen(null), MegaClient.Category.VALUABLE));
            ctx.waitTicks(5);
            ctx.takeScreenshot("04-edit-blocks");
            ctx.runOnClient(c -> MegaScreen.tab = 1);
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("03b-panel-display");
            ctx.runOnClient(c -> MegaScreen.tab = 2);
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("03c-panel-players");
            ctx.runOnClient(c -> MegaScreen.tab = 0);
            ctx.setScreen(() -> null);
            // Editing a colour's blocks: add obsidian to Storage (red), it must be found
            srv.runCommand("setblock 6 -60 4 minecraft:obsidian");
            ctx.runOnClient(c -> { MegaClient.config.storageBlocks.add("minecraft:obsidian"); MegaClient.applyConfig(); });
            ctx.waitTicks(60);
            boolean obs = ctx.computeOnClient(c -> MegaClient.targets.stream().anyMatch(x -> x.label().equals("OBSIDIAN")
                && x.category() == MegaClient.Category.STORAGE));
            check(obs, "block added to a colour was not found");
            // Switching one block off: the spawner must disappear
            ctx.runOnClient(c -> { MegaClient.config.disabledTargets.add("minecraft:spawner"); MegaClient.applyConfig(); });
            ctx.waitTicks(60);
            check(ctx.computeOnClient(c -> MegaClient.targets.stream().noneMatch(x -> x.category() == MegaClient.Category.SPAWNER)),
                "spawner still shown after switching it off");
            ctx.runOnClient(c -> { MegaClient.config.storageBlocks.remove("minecraft:obsidian");
                MegaClient.config.disabledTargets.remove("minecraft:spawner"); MegaClient.applyConfig(); });
            MegaClient.LOGGER.info("[M.E.G.A test] block editing OK");
            // R = refresh: results cleared, then a full rescan finds everything again
            ctx.runOnClient(MegaClient::refresh);
            check(ctx.computeOnClient(c -> MegaClient.targets.isEmpty()), "refresh did not clear old results");
            ctx.waitTicks(40);
            int again = ctx.computeOnClient(c -> MegaClient.targets.size());
            check(again >= 8, "refresh did not rescan (found " + again + ")");
            MegaClient.LOGGER.info("[M.E.G.A test] refresh OK, {} targets", again);
            // Farms: a real bamboo farm (observers aimed at the bamboo) is found and named right; pistons that are just
            // NEXT TO wild bamboo (like a jungle temple) are not a farm.
            for (int z = 0; z < 3; z++) {
                srv.runCommand("setblock -20 -60 " + z + " minecraft:bamboo");
                srv.runCommand("setblock -21 -59 " + z + " minecraft:observer[facing=east]");
                srv.runCommand("setblock -50 -60 " + z + " minecraft:sticky_piston[facing=up]");
                for (int dx = 1; dx <= 3; dx++) srv.runCommand("setblock " + (-50 - dx) + " -60 " + z + " minecraft:bamboo");
            }
            ctx.runOnClient(MegaClient::refresh);
            ctx.waitTicks(60);
            String farms = ctx.computeOnClient(c -> MegaClient.targets.stream().filter(t -> t.category() == MegaClient.Category.FARM)
                .map(t -> t.label() + "@" + t.pos().getX()).toList().toString());
            MegaClient.LOGGER.info("[M.E.G.A test] farms: {}", farms);
            check(farms.contains("BAMBOO_FARM"), "real bamboo farm not found: " + farms);
            check(!farms.contains("@-5"), "pistons next to wild bamboo were called a farm: " + farms);

            // Radars: a mob on the (flat, y<0) ground goes on BELOW 0, one floating high up goes on SKY
            srv.runCommand("summon minecraft:zombie 3 -60 3 {NoAI:1b,PersistenceRequired:1b}");
            srv.runCommand("summon minecraft:cow -4 -60 6 {NoAI:1b}");
            srv.runCommand("summon minecraft:zombie 6 30 2 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
            ctx.waitTicks(20);
            int[] rc = ctx.computeOnClient(c -> MegaRadar.count.clone());
            MegaClient.LOGGER.info("[M.E.G.A test] radars sky={} ground={} below0={}", rc[0], rc[1], rc[2]);
            check(rc[0] >= 1 && rc[2] >= 2, "radars wrong: sky=" + rc[0] + " below0=" + rc[2]);
            ctx.takeScreenshot("10-radars");
            srv.runCommand("kill @e[type=!minecraft:player]");

            // Underground lights (inside the hill), a lone torch on the surface (ignored), built blocks, base signs
            srv.runCommand("setblock 10 -59 7 minecraft:glowstone");
            srv.runCommand("setblock 10 -59 8 minecraft:sea_lantern");
            srv.runCommand("setblock 0 -60 -6 minecraft:torch");
            srv.runCommand("fill -10 -60 -8 -3 -60 -8 minecraft:white_concrete");
            for (int v = 0; v < 7; v++) srv.runCommand("summon minecraft:villager " + (-15 + v % 3) + " -60 " + (-12 + v / 3) + " {NoAI:1b}");
            srv.runCommand("summon minecraft:cow -6 -60 -14 {NoAI:1b,CustomName:\"Bessie\"}");
            ctx.runOnClient(MegaClient::refresh);
            ctx.waitTicks(60);
            String signs = ctx.computeOnClient(c -> MegaClient.targets.stream()
                .filter(t -> t.category() == MegaClient.Category.LIGHT || t.category() == MegaClient.Category.BUILT
                    || t.category() == MegaClient.Category.BASE).map(MegaClient.Target::label).toList().toString());
            MegaClient.LOGGER.info("[M.E.G.A test] lights/built/base signs: {}", signs);
            check(signs.contains("LIGHTS_UNDERGROUND"), "underground lights not found: " + signs);
            check(signs.contains("BUILT_BLOCKS"), "built blocks not found: " + signs);
            check(signs.contains("TRADING_HALL"), "trading hall not found: " + signs);
            check(signs.contains("NAMED_COW"), "named mob not found: " + signs);
            ctx.takeScreenshot("11-base-hunting");
            srv.runCommand("kill @e[type=!minecraft:player]");
            srv.runCommand("fill -10 -60 -8 -3 -60 -8 minecraft:air");

            // Shulker ITEMS: a dropped one and one in an item frame show what's inside; the tooltip lists everything
            String box = "{id:\"minecraft:lime_shulker_box\",count:1,components:{\"minecraft:container\":["
                + "{slot:0,item:{id:\"minecraft:diamond_block\",count:64}},{slot:1,item:{id:\"minecraft:diamond_block\",count:64}},"
                + "{slot:2,item:{id:\"minecraft:netherite_ingot\",count:5}}]}}";
            srv.runCommand("summon minecraft:item 2 -60 0 {PickupDelay:32767,Item:" + box + "}");
            srv.runCommand("summon minecraft:item_frame -2 -60 0 {Facing:1b,Item:{id:\"minecraft:red_shulker_box\",count:1}}");
            ctx.runOnClient(MegaClient::refresh);
            ctx.waitTicks(60);
            String sh = ctx.computeOnClient(c -> MegaClient.targets.stream().filter(t -> t.label().contains("SHULKER:"))
                .map(MegaClient.Target::label).toList().toString());
            String tip = ctx.computeOnClient(c -> {
                for (net.minecraft.entity.Entity e : c.world.getEntities())
                    if (e instanceof net.minecraft.entity.ItemEntity ie && MegaShulkers.isShulker(ie.getStack()))
                        return ie.getStack().getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, c.player,
                            net.minecraft.item.tooltip.TooltipType.BASIC).stream().map(net.minecraft.text.Text::getString).toList().toString();
                return "none";
            });
            MegaClient.LOGGER.info("[M.E.G.A test] shulker items: {} tooltip: {}", sh, tip);
            check(sh.contains("DROPPED_SHULKER: 128 Block of Diamond +1 more"), "dropped shulker contents wrong: " + sh);
            check(sh.contains("FRAMED_SHULKER: EMPTY"), "framed empty shulker missing: " + sh);
            check(tip.contains("M.E.G.A: 3/27 slots used, 133 items") && tip.contains("5x Netherite Ingot"), "tooltip wrong: " + tip);
            ctx.takeScreenshot("12-shulker-items");
            srv.runCommand("kill @e[type=!minecraft:player]");

            // Portals & beds, hidden storage entities, stash memory, map memory, big map, minimap layers
            srv.runCommand("setblock 12 -60 -2 minecraft:respawn_anchor");
            srv.runCommand("setblock 14 -60 -1 minecraft:red_bed[part=foot,facing=north]");
            srv.runCommand("setblock 14 -60 -2 minecraft:red_bed[part=head,facing=north]");
            srv.runCommand("summon minecraft:chest_minecart -8 -60 2");
            srv.runCommand("setblock 1 -60 -8 minecraft:oak_sign{front_text:{messages:[\"Base\",\"here\",\"\",\"\"]}}");
            ctx.runOnClient(MegaClient::refresh);
            ctx.waitTicks(80);
            String pb = ctx.computeOnClient(c -> MegaClient.targets.stream()
                .filter(t -> t.category() == MegaClient.Category.PORTAL || t.label().equals("CHEST_MINECART"))
                .map(MegaClient.Target::label).toList().toString());
            int remembered = ctx.computeOnClient(c -> MegaTracking.stashes(c).size());
            int chunksKnown = ctx.computeOnClient(c -> MegaMap.chunks(c).size());
            MegaClient.LOGGER.info("[M.E.G.A test] portals/beds/storage: {} remembered={} mapChunks={}", pb, remembered, chunksKnown);
            check(pb.contains("RESPAWN_ANCHOR") && pb.contains("RED_BED") && pb.contains("CHEST_MINECART"), "portal/bed/minecart missing: " + pb);
            check(pb.indexOf("RED_BED") == pb.lastIndexOf("RED_BED"), "bed counted twice: " + pb);
            String sign = ctx.computeOnClient(c -> MegaClient.targets.stream().filter(t -> t.label().startsWith("SIGN:"))
                .map(MegaClient.Target::label).findFirst().orElse("none"));
            MegaClient.LOGGER.info("[M.E.G.A test] sign: {}", sign);
            check(sign.equals("SIGN: \"Base / here\""), "sign text wrong: " + sign);
            check(remembered >= 8, "stash memory empty: " + remembered);
            check(chunksKnown >= 20, "map memory empty: " + chunksKnown);
            // the flat test world's ground is grass: the remembered map colour under you must be green, not dirt-brown
            int under = ctx.computeOnClient(c -> {
                byte[] ch = MegaMap.chunks(c).get(net.minecraft.util.math.ChunkPos.toLong(0, 0));
                return ch == null ? 0 : MegaMap.colour(ch[5 * 16 + 5]);
            });
            MegaClient.LOGGER.info("[M.E.G.A test] map colour at 5,5 = {}", Integer.toHexString(under));
            check(((under >> 8) & 255) > ((under >> 16) & 255), "map ground should be green (grass), got " + Integer.toHexString(under));
            ctx.takeScreenshot("13-minimap-surface");
            ctx.runOnClient(c -> { MegaClient.config.minimapLayer = 1; });
            ctx.waitTicks(15);
            ctx.takeScreenshot("14-minimap-cave");
            ctx.runOnClient(c -> { MegaClient.config.minimapLayer = 0; });
            ctx.setScreen(MegaMapScreen::new);
            ctx.waitTicks(10);
            ctx.takeScreenshot("15-big-map");
            ctx.setScreen(() -> null);
            // Elytra helper: glide for a moment and take a picture of the compass bar
            srv.runCommand("item replace entity @a armor.chest with minecraft:elytra");
            srv.runCommand("gamemode survival @a");
            srv.runCommand("tp @a 0 -20 -3 0 10");
            ctx.waitTicks(10);
            ctx.runOnClient(c -> { c.player.startGliding(); c.player.setVelocity(0, -0.2, 1.2); });
            ctx.waitTicks(3);
            boolean gliding = ctx.computeOnClient(c -> c.player.isGliding());
            MegaClient.LOGGER.info("[M.E.G.A test] gliding for elytra bar: {}", gliding);
            ctx.takeScreenshot("17-elytra-helper");
            srv.runCommand("gamemode spectator @a");
            srv.runCommand("tp @a 0 -60 -3 0 15");
            ctx.waitTicks(10);
            ctx.runOnClient(c -> MegaScreen.tab = 4);
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("16-panel-map");
            ctx.runOnClient(c -> MegaScreen.tab = 0);
            ctx.setScreen(() -> null);
            srv.runCommand("kill @e[type=minecraft:chest_minecart]");

            // Deep scan: only buried things. The hill stash (inside 6 blocks of stone) stays; the furnace/table on the hill top go.
            ctx.runOnClient(c -> { MegaClient.config.deepScan = true; MegaClient.config.save(); MegaClient.refresh(c); });
            ctx.waitTicks(40);
            int deepN = ctx.computeOnClient(c -> MegaClient.targets.size());
            boolean allBuried = ctx.computeOnClient(c -> MegaClient.targets.stream().allMatch(t -> MegaClient.underground(c, t.pos())));
            MegaClient.LOGGER.info("[M.E.G.A test] deep scan: {} buried targets", deepN);
            check(deepN > 0 && allBuried, "deep scan should list only buried targets (" + deepN + ")");
            ctx.runOnClient(c -> { MegaClient.config.deepScan = false; MegaClient.config.save(); MegaClient.refresh(c); });
            ctx.waitTicks(40);
            ctx.setScreen(() -> null);

            // Farm alert: lots of moving dropped items
            for (int i = 0; i < 40; i++) {
                srv.runCommand("summon minecraft:item " + (i % 8 - 4) + " -55 4 {Item:{id:\"minecraft:diamond\",count:1},Motion:[0.0,0.5,0.0],PickupDelay:32767}");
            }
            ctx.waitTicks(30);
            int farm = ctx.computeOnClient(c -> MegaClient.farmCount);
            MegaClient.LOGGER.info("[M.E.G.A test] movingItems={}", farm);
            ctx.takeScreenshot("05-farm-alert");

            // Stash 100+ blocks straight below you (flat world bottom is y=-64): must still be found and shown in X-ray
            srv.runCommand("kill @e[type=minecraft:item]");
            srv.runCommand("setblock 30 -62 30 minecraft:spawner");
            srv.runCommand("setblock 31 -62 30 minecraft:chest");
            srv.runCommand("setblock 29 -62 30 minecraft:diamond_block");
            srv.runCommand("setblock 30 -62 31 minecraft:lime_shulker_box");
            srv.runCommand("gamemode spectator @a"); // hover in place (no falling)
            ctx.runOnClient(c -> c.options.getViewDistance().setValue(12)); // fog would hide ground 112 blocks away at the test default
            srv.runCommand("tp @a 30.5 51 27.5 0 82");
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(c -> { c.player.setYaw(0); c.player.setPitch(80); }); // look straight down
            ctx.waitTicks(80);
            double deep = ctx.computeOnClient(c -> MegaClient.targets.stream()
                .filter(t -> t.pos().getX() >= 29 && t.pos().getX() <= 31 && t.pos().getZ() >= 30 && t.pos().getZ() <= 31)
                .mapToDouble(MegaClient.Target::distance).max().orElse(-1));
            MegaClient.LOGGER.info("[M.E.G.A test] deep stash found at {} blocks", Math.round(deep));
            check(deep >= 100, "stash 100+ blocks below was not found (got " + deep + ")");
            ctx.takeScreenshot("06-100-below");
            ctx.runOnClient(c -> { MegaClient.config.xray = true; MegaClient.applyConfig(); });
            ctx.waitTicks(200); // software rendering on CI is slow at render distance 12; just give it 10 s
            ctx.waitTicks(20);
            ctx.takeScreenshot("07-100-below-xray");
            ctx.runOnClient(c -> { MegaClient.config.xray = false; MegaClient.applyConfig(); });
            // Waypoints: add one, then die and check the death is saved
            ctx.runOnClient(c -> MegaWaypoints.addHere(c, "Test base"));
            srv.runCommand("gamemode survival @a");
            ctx.waitTicks(5);
            srv.runCommand("kill @a");
            ctx.waitTicks(20);
            long deaths = ctx.computeOnClient(c -> MegaWaypoints.list(c).stream().filter(w -> w.death).count());
            boolean base = ctx.computeOnClient(c -> MegaWaypoints.list(c).stream().anyMatch(w -> w.name.equals("Test base")));
            MegaClient.LOGGER.info("[M.E.G.A test] waypoint saved={} deaths saved={}", base, deaths);
            check(base, "waypoint was not saved");
            check(deaths >= 1, "death point was not saved");
            ctx.runOnClient(c -> { c.player.requestRespawn(); c.setScreen(null); });
            ctx.waitTicks(20);
            srv.runCommand("gamemode spectator @a");
            srv.runCommand("tp @a 0 -60 -3 0 15");
            ctx.runOnClient(c -> MegaScreen.tab = 3);
            ctx.setScreen(() -> new MegaScreen(null));
            ctx.waitTicks(5);
            ctx.takeScreenshot("08-waypoints-panel");
            ctx.setScreen(() -> null);
            ctx.waitTicks(20);
            ctx.takeScreenshot("09-waypoints-hud");
            ctx.runOnClient(c -> MegaScreen.tab = 0);
            MegaClient.LOGGER.info("[M.E.G.A test] ALL CHECKS PASSED");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError("[M.E.G.A test] " + message);
    }
}
