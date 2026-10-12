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
    private int contentTop, contentEnd;
    /** Dark panel behind the buttons: left, top, right, bottom. */
    private int[] box = new int[4];

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
            int toggleW = (int) (w * 0.66);
            for (Category cat : Category.values()) {
                addDrawableChild(ButtonWidget.builder(colourText(cat, cat.enabled(c)), b -> {
                    cat.setEnabled(c, !cat.enabled(c));
                    b.setMessage(colourText(cat, cat.enabled(c)));
                }).dimensions(x0, y, toggleW, BTN_H).build());
                List<String> blocks = cat.blocks(c);
                long on = blocks.stream().filter(id -> !c.disabledTargets.contains(id)).count();
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit  §7" + on + "/" + blocks.size() + " §f›"),
                    b -> client.setScreen(new MegaBlocksScreen(this, cat)))
                    .dimensions(x0 + toggleW + GAP, y, w - toggleW - GAP, BTN_H).build());
                y += BTN_H + GAP;
            }
        } else if (tab == 1) {
            // Screen | Lines | X-ray | Range, two per row
            y = toggleGrid(List.of(
                t("HUD", () -> c.hudEnabled, v -> c.hudEnabled = v),
                t("Blocks seen panel", () -> c.blockCountPanel, v -> c.blockCountPanel = v),
                t("Lines", () -> c.tracers, v -> c.tracers = v),
                t("Boxes", () -> c.boxes, v -> c.boxes = v)), x0, y, colW);
            addDrawableChild(new IntSlider(x0, y, colW, "Lines shown", 0, 50, () -> c.maxTracers, v -> c.maxTracers = v));
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "List rows", 1, 30, () -> c.maxLines, v -> c.maxLines = v));
            y += BTN_H + GAP + 4;
            y = toggleGrid(List.of(
                t("X-ray", () -> c.xray, v -> { c.xray = v; MegaClient.applyConfig(); }),
                t("X-ray outlines", () -> c.xrayGhost, v -> c.xrayGhost = v),
                t("X-ray lava & water", () -> c.xrayFluids, v -> c.xrayFluids = v),
                t("Ores after stashes", () -> c.oresLast, v -> c.oresLast = v)), x0, y, colW) + 4;
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
            y += 14;
            ignoredField = new TextFieldWidget(textRenderer, x0, y, w, BTN_H, Text.literal("Ignored players"));
            ignoredField.setMaxLength(512);
            ignoredField.setText(String.join(", ", c.ignoredPlayers));
            ignoredField.setPlaceholder(Text.literal("Names, comma separated"));
            addDrawableChild(ignoredField);
            y += BTN_H + GAP;
        }

        contentEnd = y;
        int doneY = Math.min(height - BTN_H - 4, y + 16);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(x0, doneY, w, BTN_H).build());
        box = new int[]{x0 - 6, 1, x0 + w + 6, doneY + BTN_H + 5};
    }

    /** Blurred game behind, then one dark panel holding everything. */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        panel(ctx, box);
    }

    static void panel(DrawContext ctx, int[] b) {
        ctx.fill(b[0], b[1], b[2], b[3], 0xC0101014);
        ctx.fill(b[0], b[1], b[2], b[1] + 1, 0xFFFFD400); // gold line on top
        ctx.fill(b[0], b[3] - 1, b[2], b[3], 0x60FFFFFF);
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

    /** "■ Ores  ON" with the square and name in the colour itself. */
    static MutableText colourText(Category cat, boolean on) {
        return Text.literal("■ " + cat.title).withColor(MegaClient.readable(cat.color) & 0xFFFFFF)
            .append(Text.literal(on ? "  §aON" : "  §cOFF"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 4, 0xFFFFD400);
        if (ignoredField != null) {
            ctx.drawTextWithShadow(textRenderer, "Ignored players (never detected):",
                ignoredField.getX(), ignoredField.getY() - 11, 0xFFAAAAAA);
        }
        // Footer hint, just above Done
        int hy = box[3] - BTN_H - 17;
        if (hy >= contentEnd - GAP) {
            String hint = tab == 0 ? "Every colour that's ON always gets a line  ·  R refreshes"
                : "Saves when you press Done  ·  R refreshes";
            ctx.drawCenteredTextWithShadow(textRenderer, hint, width / 2, hy, 0xFF8A8A8A);
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
