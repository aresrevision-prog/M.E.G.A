package dev.mega.client;

import dev.mega.client.MegaClient.Category;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Settings panel (default key: 0), in three tabs:
 * Colours (each colour on/off + "Edit blocks"), Display (HUD, lines, X-ray, ranges) and Players. Saves on close.
 */
final class MegaScreen extends Screen {
    static final int BTN_H = 20, GAP = 3;
    private static final String[] TABS = {"Colours & blocks", "Display", "Players"};
    /** Remembered while the game runs, so the panel reopens on the tab you used last. */
    static int tab = 0;
    private final Screen parent;
    private TextFieldWidget ignoredField;
    private int contentTop;

    MegaScreen(Screen parent) {
        super(Text.literal("M.E.G.A Client v" + MegaClient.version));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MegaConfig c = MegaClient.config;
        ignoredField = null;
        int w = Math.min(320, width - 12);
        int x0 = width / 2 - w / 2;
        int colW = (w - GAP) / 2;

        // Tabs
        int tabW = (w - 2 * GAP) / 3;
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(i == tab ? "§e" + TABS[i] : TABS[i]), btn -> {
                saveIgnored();
                tab = t;
                clearAndInit();
            }).dimensions(x0 + i * (tabW + GAP), 20, tabW, BTN_H).build();
            b.active = i != tab;
            addDrawableChild(b);
        }
        int y = contentTop = 48;

        if (tab == 0) {
            // One row per colour: [■ Ores (cyan): ON] [Edit blocks (5/5) >]
            int toggleW = (int) (w * 0.58);
            for (Category cat : Category.values()) {
                addDrawableChild(ButtonWidget.builder(colourText(cat, cat.enabled(c)), b -> {
                    cat.setEnabled(c, !cat.enabled(c));
                    b.setMessage(colourText(cat, cat.enabled(c)));
                }).dimensions(x0, y, toggleW, BTN_H).build());
                List<String> blocks = cat.blocks(c);
                long on = blocks.stream().filter(id -> !c.disabledTargets.contains(id)).count();
                String what = cat == Category.FARM ? "Edit plants" : "Edit blocks";
                addDrawableChild(ButtonWidget.builder(Text.literal(what + " (" + on + "/" + blocks.size() + ") >"),
                    b -> client.setScreen(new MegaBlocksScreen(this, cat)))
                    .dimensions(x0 + toggleW + GAP, y, w - toggleW - GAP, BTN_H).build());
                y += BTN_H + GAP;
            }
        } else if (tab == 1) {
            List<Object[]> toggles = List.of(
                t("HUD", () -> c.hudEnabled, v -> c.hudEnabled = v),
                t("Blocks seen panel", () -> c.blockCountPanel, v -> c.blockCountPanel = v),
                t("Tracer lines", () -> c.tracers, v -> c.tracers = v),
                t("Boxes", () -> c.boxes, v -> c.boxes = v),
                t("M.E.G.A X-ray", () -> c.xray, v -> { c.xray = v; MegaClient.applyConfig(); }),
                t("X-ray outlines", () -> c.xrayGhost, v -> c.xrayGhost = v),
                t("X-ray lava & water", () -> c.xrayFluids, v -> c.xrayFluids = v),
                t("Natural ores listed last", () -> c.oresLast, v -> c.oresLast = v));
            y = toggleGrid(toggles, x0, y, colW);
            addDrawableChild(new IntSlider(x0, y, colW, "Max lines", 0, 50, () -> c.maxTracers, v -> c.maxTracers = v));
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "List rows", 1, 30, () -> c.maxLines, v -> c.maxLines = v));
            y += BTN_H + GAP;
            addDrawableChild(new IntSlider(x0, y, colW, "Block range", 8, 1250, () -> c.maxDistance, v -> c.maxDistance = v));
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "Ore range", 8, 1250, () -> c.oreRange, v -> c.oreRange = v));
            y += BTN_H + GAP;
        } else {
            List<Object[]> toggles = List.of(
                t("Player warning", () -> c.playerWarning, v -> c.playerWarning = v),
                t("Player list", () -> c.playerList, v -> c.playerList = v),
                t("Player lines", () -> c.tracePlayers, v -> c.tracePlayers = v),
                t("Invisible players", () -> c.showInvisiblePlayers, v -> c.showInvisiblePlayers = v),
                t("Outline sneaky players", () -> c.outlineSneaky, v -> c.outlineSneaky = v));
            y = toggleGrid(toggles, x0, y, colW);
            addDrawableChild(new IntSlider(x0 + colW + GAP, y - BTN_H - GAP, colW, "Player range", 8, 1250,
                () -> c.playerRange, v -> c.playerRange = v));
            y += 12;
            ignoredField = new TextFieldWidget(textRenderer, x0, y, w, BTN_H, Text.literal("Ignored players"));
            ignoredField.setMaxLength(512);
            ignoredField.setText(String.join(", ", c.ignoredPlayers));
            ignoredField.setPlaceholder(Text.literal("Names, comma separated"));
            addDrawableChild(ignoredField);
            y += BTN_H + GAP;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(width / 2 - 75, Math.min(height - BTN_H - 4, y + 6), 150, BTN_H).build());
    }

    private static Object[] t(String name, Supplier<Boolean> get, Consumer<Boolean> set) {
        return new Object[]{name, get, set};
    }

    /** Two-column grid of ON/OFF buttons; returns the y below it. */
    @SuppressWarnings("unchecked")
    private int toggleGrid(List<Object[]> toggles, int x0, int y, int colW) {
        for (int i = 0; i < toggles.size(); i++) {
            String name = (String) toggles.get(i)[0];
            Supplier<Boolean> get = (Supplier<Boolean>) toggles.get(i)[1];
            Consumer<Boolean> set = (Consumer<Boolean>) toggles.get(i)[2];
            addDrawableChild(ButtonWidget.builder(onOff(name, get.get()), b -> {
                set.accept(!get.get());
                b.setMessage(onOff(name, get.get()));
            }).dimensions(x0 + (i % 2) * (colW + GAP), y + (i / 2) * (BTN_H + GAP), colW, BTN_H).build());
        }
        return y + ((toggles.size() + 1) / 2) * (BTN_H + GAP);
    }

    static Text onOff(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    /** "■ Ores (cyan): ON" with the square in the colour itself. */
    static MutableText colourText(Category cat, boolean on) {
        return Text.literal("■ ").withColor(MegaClient.readable(cat.color) & 0xFFFFFF)
            .append(Text.literal(cat.title + " (" + cat.colourName + "): " + (on ? "§aON" : "§cOFF")));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, 0xFFFFD400);
        if (ignoredField != null) {
            ctx.drawTextWithShadow(textRenderer, "Ignored players (never detected):",
                ignoredField.getX(), ignoredField.getY() - 11, 0xFFAAAAAA);
        }
        if (tab == 0) {
            int y = contentTop + Category.values().length * (BTN_H + GAP) + 2;
            if (y + 34 < height)
                ctx.drawCenteredTextWithShadow(textRenderer, "Every colour that's ON always gets a line and a list row.",
                    width / 2, y, 0xFFAAAAAA);
        }
    }

    @Override
    public void close() {
        saveIgnored();
        MegaClient.config.save();
        MegaClient.applyConfig();
        client.setScreen(parent);
    }

    private void saveIgnored() {
        if (ignoredField == null) return;
        List<String> names = new ArrayList<>();
        for (String n : ignoredField.getText().split(",")) {
            String s = n.trim();
            if (!s.isEmpty()) names.add(s);
        }
        MegaClient.config.ignoredPlayers = names;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Slider for a whole-number setting. */
    static final class IntSlider extends SliderWidget {
        private final String name;
        private final int min, max;
        private final IntConsumer set;

        IntSlider(int x, int y, int w, String name, int min, int max, IntSupplier get, IntConsumer set) {
            super(x, y, w, BTN_H, Text.empty(), (Math.clamp(get.getAsInt(), min, max) - min) / (double) (max - min));
            this.name = name;
            this.min = min;
            this.max = max;
            this.set = set;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            int v = current();
            setMessage(Text.literal(name + ": " + (max >= 1250 && v >= max ? "Max render" : String.valueOf(v))));
        }

        @Override
        protected void applyValue() {
            set.accept(current());
        }
    }
}
