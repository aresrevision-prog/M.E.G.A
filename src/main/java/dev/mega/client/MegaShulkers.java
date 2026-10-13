package dev.mega.client;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shulker box ITEMS carry their contents with them, so the game knows what's inside: dropped on the ground, in item
 * frames, held by players, and in any inventory you open. (Placed shulker boxes don't: the server never sends those.)
 */
public final class MegaShulkers {
    private MegaShulkers() {}

    public static boolean isShulker(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Item name -> total count, biggest first. */
    static Map<String, Integer> contents(ItemStack s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        if (c == null) return m;
        for (ItemStack it : c.iterateNonEmpty()) m.merge(it.getName().getString(), it.getCount(), Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** "128 Diamond Block +3 more" or "EMPTY". */
    static String summary(ItemStack s) {
        Map<String, Integer> m = contents(s);
        if (m.isEmpty()) return "EMPTY";
        Map.Entry<String, Integer> top = m.entrySet().iterator().next();
        return top.getValue() + " " + top.getKey() + (m.size() > 1 ? " +" + (m.size() - 1) + " more" : "");
    }

    /** Extra tooltip lines: used slots / total items, then every item type with its total. */
    public static void addTooltip(ItemStack s, List<Text> lines) {
        if (!isShulker(s) || MegaClient.config == null || !MegaClient.config.shulkerPeek) return;
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        int used = 0, items = 0;
        if (c != null) for (ItemStack it : c.iterateNonEmpty()) { used++; items += it.getCount(); }
        lines.add(Text.literal("M.E.G.A: " + (used == 0 ? "EMPTY" : used + "/27 slots used, " + items + " items"))
            .formatted(used == 0 ? Formatting.GRAY : Formatting.GREEN));
        for (Map.Entry<String, Integer> e : contents(s).entrySet())
            lines.add(Text.literal("  " + e.getValue() + "x " + e.getKey()).formatted(Formatting.GRAY));
    }

    /** Dropped / framed / held shulker items in range become green targets named after what's inside. */
    static void scan(MinecraftClient client, List<MegaClient.Target> found, Map<MegaClient.Category, Integer> counts) {
        if (!MegaClient.Category.SHULKER.enabled(MegaClient.config)) return;
        double maxSq = (double) MegaClient.config.maxDistance * MegaClient.config.maxDistance;
        for (Entity e : client.world.getEntities()) {
            double sq = client.player.squaredDistanceTo(e);
            if (sq > maxSq || e == client.player) continue;
            if (e instanceof ItemEntity ie && isShulker(ie.getStack()))
                add(found, counts, "DROPPED_SHULKER: " + summary(ie.getStack()), e, sq);
            else if (e instanceof ItemFrameEntity f && isShulker(f.getHeldItemStack()))
                add(found, counts, "FRAMED_SHULKER: " + summary(f.getHeldItemStack()), e, sq);
            else if (e instanceof AbstractClientPlayerEntity p && !MegaClient.skipPlayer(client, p)) {
                ItemStack h = isShulker(p.getMainHandStack()) ? p.getMainHandStack() : p.getOffHandStack();
                if (isShulker(h))
                    add(found, counts, p.getName().getString().toUpperCase(Locale.ROOT) + " HOLDS SHULKER: " + summary(h), e, sq);
            }
        }
    }

    private static void add(List<MegaClient.Target> found, Map<MegaClient.Category, Integer> counts, String label, Entity e, double sq) {
        found.add(new MegaClient.Target(MegaClient.Category.SHULKER, label, e.getBlockPos(), Math.sqrt(sq)));
        counts.merge(MegaClient.Category.SHULKER, 1, Integer::sum);
    }
}
