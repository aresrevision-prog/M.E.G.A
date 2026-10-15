package dev.mega.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Danger alert: withers, wardens, elder guardians, the ender dragon and other very dangerous mobs near you.
 * Flashing red banner (MegaHud), thick red line + box through walls (MegaTracers), a bell sound and a chat note
 * the first time each one is seen. The list is editable in the config (dangerMobs).
 */
public final class MegaDanger {
    record Danger(Entity entity, String name, double distance) {}
    static List<Danger> near = List.of();
    private static Set<UUID> known = new HashSet<>();
    private static int timer;

    private MegaDanger() {}

    static void tick(MinecraftClient client) {
        MegaConfig cfg = MegaClient.config;
        if (!cfg.dangerAlert || client.world == null || client.player == null) { near = List.of(); known.clear(); return; }
        if (++timer < 5) return;
        timer = 0;
        Set<String> ids = new HashSet<>();
        for (String s : cfg.dangerMobs) ids.add(s.contains(":") ? s : "minecraft:" + s);
        double maxSq = (double) cfg.dangerRange * cfg.dangerRange;
        List<Danger> out = new ArrayList<>();
        Set<UUID> now = new HashSet<>();
        for (Entity e : client.world.getEntities()) {
            if (!e.isAlive()) continue;
            String id = Registries.ENTITY_TYPE.getId(e.getType()).toString();
            if (!ids.contains(id)) continue;
            double sq = client.player.squaredDistanceTo(e);
            if (sq > maxSq) continue;
            String name = Registries.ENTITY_TYPE.getId(e.getType()).getPath().replace('_', ' ').toUpperCase(Locale.ROOT);
            out.add(new Danger(e, name, Math.sqrt(sq)));
            now.add(e.getUuid());
            if (!known.contains(e.getUuid())) { // first time we see this one
                client.player.sendMessage(Text.literal("[M.E.G.A] ").formatted(Formatting.GOLD)
                    .append(Text.literal("DANGER: " + name + " " + Math.round(Math.sqrt(sq)) + "m away").formatted(Formatting.RED, Formatting.BOLD)), false);
                if (cfg.dangerSound) client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.6f));
            }
        }
        out.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        near = out;
        known = now;
    }
}
