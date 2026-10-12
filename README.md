# M.E.G.A Client — Fabric 1.21.11

Client-side info HUD + tracers. **Info only — no automation, no combat aids, no packet tricks.**

## Server rules
1. No griefing
2. No spawn camping
3. No TP killing
4. ESPs are allowed **only after the server owner has checked them**. This mod shows its version on the HUD (`M.E.G.A v0.17.0`) so the owner can confirm which approved build a player is running.
5. Duping is allowed **only while the server owner is watching**.

## Features & colours
| Colour | What |
|---|---|
| Purple | Spawners |
| Yellow / black | Other players (warning banner, player list, striped tracer). Never you. |
| Green | Shulker boxes |
| Blue | Enchanting tables, ender chests, beacons, and **Valuable blocks** (one button): netherite, ancient debris, deepslate emerald ore, deepslate coal ore, diamond, emerald, gold blocks, quartz block / chiseled / pillar / bricks / smooth (no ore), anvils, crafting/smithing tables, grindstones, stonecutters, looms, cartography & fletching tables, furnaces, smokers, blast furnaces |
| Red | Chests, barrels, hoppers, crafters, dispensers, droppers |
| Orange | **Farms**: built kelp / sugar cane / cactus / bamboo / pumpkin / melon farms (plants next to observers or pistons), and any observer/piston machinery below y=0. Buttons: *Plant farms*, *Farms below y=0* |
| Cyan | Ores: diamond (+deepslate), emerald, gold (+deepslate). No coal, copper, iron, redstone or lapis. |

- **Farm alert**: orange `FARM DETECTED` banner at the top centre for (a) the nearest built farm (e.g. `KELP FARM 40m ↗`, `UNDERGROUND FARM`) and/or (b) more than 32 dropped items moving within render distance. Natural kelp/cane is ignored: a plant farm needs observers or pistons with it. Farms also appear in the list with orange tracers.
- **Blocks seen** panel (top-right): how many of each block type are in range (every block, e.g. `DIAMOND 23`, `CHEST 41`), coloured by group, biggest first. Panel button: *Blocks seen panel*.
- **Player warning** banner when another player is in line of sight.
- **Sneaky player outlines**: box outline through walls round **crouching** players (yellow) and **invisible** players (magenta). Panel button: *Outline sneaky players*.
- **M.E.G.A X-ray** (panel button): every other block turns invisible; ores, valuable blocks, spawners, shulkers and storage stay solid with their real textures (like quartz and ores). Hidden blocks next to caves/air within 12 blocks keep an **outline in their own colour** (stone grey, dirt brown, deepslate dark grey…) so you can see the terrain shape (panel: *X-ray outlines*). Optional coloured edges round X-ray blocks: `xrayEdges` (off). Client-side night vision keeps them bright. Toggling rebuilds chunks (brief stutter). Header shows `X-RAY`. Block hiding doesn't work with Sodium (edges still do). Water/lava still show.
- **Player list**: name, distance, direction arrow. Bright yellow = visible, dim yellow = behind cover. No player coordinates.
- **Block radar** for loaded chunks, nearest first, with arrow, distance, coords and block name (e.g. `RED_SHULKER_BOX`). Double chests count once.
- **Ore / expensive-block finder** within `oreRange` (one entry per vein; sections without them are skipped, so it's cheap).
- **Tracers + boxes** to the nearest targets, through walls. Player tracers are yellow/black striped and **on** (`tracePlayers`).
- Header shows version and counts (`O` ore, `Sp` spawner, `V` valuable, `Sh` shulker, `St` storage). Hides while F3 is open.

## Controls
- `0` — open the settings panel: on/off buttons, range sliders, ignored players, and **What to detect...** (one on/off button per block type: chest, hopper, diamond ore, etc., plus All on / All off). Saves on Done.
- Reload config — unbound by default; set it in Options → Controls → M.E.G.A Client

## Config — `config/mega_client.json`
| Key | Default | Notes |
|---|---|---|
| hudEnabled | true | |
| hudX / hudY | 8 / 8 | HUD position |
| maxLines | 8 | rows per list (1–30) |
| blockCountPanel | true | Blocks seen corner panel |
| blockCountLines | 15 | rows in that panel (1–40) |
| playerWarning / playerList | true / true | |
| showInvisiblePlayers | false | |
| outlineSneaky | true | outline crouching / invisible players |
| xray | false | M.E.G.A X-ray on/off |
| xrayGhost | true | outlines of hidden blocks in their own colour |
| xrayGhostRange | 12 | 4–32 blocks around you |
| xrayGhostMax | 6000 | most outlines at once |
| xrayEdges | false | coloured edges round X-ray blocks |
| xrayMax | 3000 | most blocks outlined at once (lower = more FPS) |
| disabledTargets | [] | individual block types switched off (set from the panel) |
| ignoredPlayers | ["IzzosBro2013"] | never detected: no line, not listed, no warning (case-insensitive) |
| playerRange | 1250 | 8–1250 (1250 = max render distance) |
| spawners / valuables / shulkers / storage | true | category toggles |
| maxDistance | 1250 | 8–1250; default searches your whole render distance like the original mod |
| scanIntervalTicks | 20 | 5–200, pause between scans |
| chunksPerTick | 24 | scan is spread over ticks, nearest chunks first, so no lag spikes |
| tracers / boxes | true / true | lines and box outlines |
| tracePlayers | true | yellow/black lines to players in `playerRange` |
| maxTracers | 8 | 0–50, nearest targets get lines |
| lineWidth | 2.0 | 0.5–10 |
| farmAlert | true | farm banner on/off |
| farmThreshold | 32 | alert when MORE than this many items are moving |
| ignoredItems | stone, cobblestone, cobbled deepslate, deepslate, gravel | dropped items that never count for the moving-items alert |
| farmRange | 1250 | farms and moving items (1250 = max render) |
| ores | true | ore finder on/off |
| oreRange | 1250 | ores and valuable blocks (1250 = max render) |
| valuableBlocks | see Blue above | any block IDs; all share the one Valuable blocks button |
| oreBlocks | diamond, deepslate diamond, ancient debris, emerald, deepslate emerald | any block IDs, e.g. `"minecraft:gold_ore"` |

## Build
**Easiest (no setup):** push this folder to a GitHub repo. The included Actions workflow builds it; download the JAR from the run's *Artifacts* (`mega-client-jar`).

**Locally:** JDK 21, then `./gradlew build` (Windows: `gradlew.bat build`). JAR is in `build/libs/` (use the one without `-sources`). Put it in `.minecraft/mods` with Fabric API.

## Limits (by design / impossible client-side)
- Only sees chunks your client has loaded — the server never sends unloaded chunks.
- Can't see chest contents until you open them — the server doesn't send them.
- If the server runs an anti-xray plugin (e.g. Paper's), hidden ores are fake and the ore finder will show wrong results.
- No combat, movement, teleport or auto-break features — those would break rules 1–3.
