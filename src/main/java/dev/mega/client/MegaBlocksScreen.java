package dev.mega.client;

import dev.mega.client.MegaClient.Category;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.mega.client.MegaScreen.BTN_H;
import static dev.mega.client.MegaScreen.GAP;

/**
 * Edit the blocks of one colour: click a block to turn it on/off, X to remove it, type a block name and press Add.
 * Adding a block that's in another colour moves it here.
 */
final class MegaBlocksScreen extends Screen {
    private final Screen parent;
    private final Category cat;
    private int page;
    private String typed = "";
    private String status = "";
    private int statusColor = 0xFFAAAAAA;
    private List<String> matches = List.of();
    private TextFieldWidget field;
    private int pages = 1;
    private int[] box = new int[4];

    MegaBlocksScreen(Screen parent, Category cat) {
        super(Text.literal("■ ").withColor(MegaClient.readable(cat.color) & 0xFFFFFF)
            .append(Text.literal(cat.title + " (" + cat.colourName + ")" + (cat == Category.FARM ? " - farm plants" : " - blocks"))));
        this.parent = parent;
        this.cat = cat;
    }

    @Override
    protected void init() {
        MegaConfig c = MegaClient.config;
        List<String> list = cat.blocks(c);
        List<String> off = c.disabledTargets;
        int w = Math.min(330, width - 12);
        int x0 = width / 2 - w / 2;
        int colW = (w - GAP) / 2;

        // Bottom area: [farm-only toggle] / name field + Add + Reset / hint line / nav row
        int navY = height - BTN_H - 4;
        int fieldY = navY - BTN_H - 14;
        int extraY = fieldY - BTN_H - GAP;
        int listBottom = cat == Category.FARM ? extraY - GAP : fieldY - GAP;

        int top = 34;
        int rows = Math.max(1, (listBottom - top) / (BTN_H + GAP));
        int perPage = rows * 2;
        pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        page = Math.clamp(page, 0, pages - 1);

        for (int i = page * perPage, n = 0; i < list.size() && n < perPage; i++, n++) {
            String id = list.get(i);
            int x = x0 + (n % 2) * (colW + GAP);
            int y = top + (n / 2) * (BTN_H + GAP);
            addDrawableChild(ButtonWidget.builder(blockText(id, !off.contains(id)), b -> {
                if (!off.remove(id)) off.add(id);
                b.setMessage(blockText(id, !off.contains(id)));
            }).dimensions(x, y, colW - 22, BTN_H).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§cX"), b -> {
                list.remove(id);
                off.remove(id);
                say("Removed " + pretty(id), 0xFFFFAA55);
                clearAndInit();
            }).dimensions(x + colW - 20, y, 20, BTN_H).build());
        }

        if (cat == Category.FARM) {
            addDrawableChild(ButtonWidget.builder(farmText(!off.contains("underground_farm")), b -> {
                if (!off.remove("underground_farm")) off.add("underground_farm");
                b.setMessage(farmText(!off.contains("underground_farm")));
            }).dimensions(x0, extraY, w, BTN_H).build());
        }

        // Add a block
        int btnW = 56;
        field = new TextFieldWidget(textRenderer, x0, fieldY, w - 2 * (btnW + GAP), BTN_H, Text.literal("Block name"));
        field.setMaxLength(100);
        field.setText(typed);
        field.setPlaceholder(Text.literal("Type a block, e.g. diamond_block"));
        field.setChangedListener(s -> { typed = s; updateMatches(); });
        addDrawableChild(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("§aAdd"), b -> add())
            .dimensions(x0 + w - 2 * btnW - GAP, fieldY, btnW, BTN_H).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
            list.clear();
            list.addAll(cat.defaults());
            off.removeAll(cat.defaults());
            page = 0;
            say("Back to the default " + cat.colourName + " blocks", 0xFF55FF55);
            clearAndInit();
        }).dimensions(x0 + w - btnW, fieldY, btnW, BTN_H).build());

        // Nav row
        int arrowW = 40;
        int midW = (w - 2 * arrowW - 4 * GAP) / 3;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { page--; clearAndInit(); })
            .dimensions(x0, navY, arrowW, BTN_H).build();
        prev.active = page > 0;
        addDrawableChild(prev);
        int x = x0 + arrowW + GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("§aAll on"), b -> { off.removeAll(list); clearAndInit(); })
            .dimensions(x, navY, midW, BTN_H).build());
        x += midW + GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("§cAll off"), b -> {
            for (String id : list) if (!off.contains(id)) off.add(id);
            clearAndInit();
        }).dimensions(x, navY, midW, BTN_H).build());
        x += midW + GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close()).dimensions(x, navY, midW, BTN_H).build());
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { page++; clearAndInit(); })
            .dimensions(x0 + w - arrowW, navY, arrowW, BTN_H).build();
        next.active = page < pages - 1;
        addDrawableChild(next);
        box = new int[]{x0 - 6, 1, x0 + w + 6, navY + BTN_H + 3};
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        MegaScreen.panel(ctx, box);
    }

    /** Adds the typed block (or the only match), moving it out of any other colour. */
    private void add() {
        String id = resolve(typed);
        if (id == null) {
            if (matches.size() == 1) id = matches.get(0);
            else {
                say(typed.isBlank() ? "Type a block name first" : matches.isEmpty() ? "No block called \"" + typed.trim() + "\""
                    : "More than one match: type more of the name", 0xFFFF5555);
                clearAndInit();
                return;
            }
        }
        MegaConfig c = MegaClient.config;
        List<String> list = cat.blocks(c);
        if (list.contains(id)) {
            say(pretty(id) + " is already here", 0xFFFFAA55);
        } else {
            String from = null;
            if (cat != Category.FARM) for (Category other : Category.values()) {
                if (other != cat && other != Category.FARM && other.blocks(c).remove(id)) from = other.title;
            }
            list.add(id);
            c.disabledTargets.remove(id);
            say("Added " + pretty(id) + (from != null ? " (moved from " + from + ")" : ""), 0xFF55FF55);
            typed = "";
            matches = List.of();
            page = Integer.MAX_VALUE; // jump to the last page, where it was added
        }
        clearAndInit();
    }

    /** "diamond_block" or "minecraft:diamond_block" -> "minecraft:diamond_block" if that block exists. */
    private static String resolve(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (t.isEmpty()) return null;
        Identifier id = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
        return id != null && Registries.BLOCK.containsId(id) && !t.endsWith(":air") ? id.toString() : null;
    }

    private void updateMatches() {
        String t = typed.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (t.length() < 2) { matches = List.of(); return; }
        List<String> exact = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Identifier id : Registries.BLOCK.getIds()) {
            String path = id.getPath(), full = id.toString();
            if (path.equals(t) || full.equals(t)) exact.add(full);
            else if (path.startsWith(t)) starts.add(full);
            else if (path.contains(t)) contains.add(full);
        }
        starts.sort(null);
        contains.sort(null);
        List<String> all = new ArrayList<>(exact);
        all.addAll(starts);
        all.addAll(contains);
        matches = all;
    }

    private void say(String msg, int color) {
        status = msg;
        statusColor = color;
    }

    private static Text blockText(String id, boolean on) {
        boolean known = resolve(id) != null;
        return Text.literal((known ? pretty(id) : "§7" + pretty(id) + " (?)") + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Text farmText(boolean on) {
        return MegaScreen.onOff("Machines below y=0 (observers/pistons)", on);
    }

    /** "minecraft:deepslate_diamond_ore" -> "Deepslate diamond ore". */
    static String pretty(String id) {
        int colon = id.indexOf(':');
        String ns = colon > 0 ? id.substring(0, colon) : "minecraft";
        String s = (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
        if (s.isEmpty()) return id;
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return ns.equals("minecraft") ? s : ns + ": " + s;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        List<String> list = cat.blocks(MegaClient.config);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xFFFFFFFF);
        String sub = list.size() + (cat == Category.FARM ? " plants" : " blocks") + "  ·  click: on/off  ·  X: remove"
            + (pages > 1 ? "  ·  page " + (page + 1) + "/" + pages : "");
        ctx.drawCenteredTextWithShadow(textRenderer, sub, width / 2, 20, 0xFFAAAAAA);
        if (list.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, "No blocks yet: type one below and press Add", width / 2, 50, 0xFFFFAA55);
        // Hint line under the name box: matches while typing, otherwise the last message
        String hint;
        int color;
        if (!matches.isEmpty() && !typed.isBlank()) {
            List<String> shown = new ArrayList<>();
            for (String m : matches) {
                if (shown.size() == 4) break;
                shown.add(m.startsWith("minecraft:") ? m.substring(10) : m);
            }
            hint = "Matches: " + String.join(", ", shown) + (matches.size() > 4 ? " +" + (matches.size() - 4) : "");
            color = 0xFFAAAAAA;
        } else {
            hint = status;
            color = statusColor;
        }
        if (!hint.isEmpty() && field != null)
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(hint, width - field.getX() - 4), field.getX(),
                field.getY() + BTN_H + 3, color);
    }

    @Override
    public void close() {
        MegaClient.config.save();
        MegaClient.applyConfig();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
