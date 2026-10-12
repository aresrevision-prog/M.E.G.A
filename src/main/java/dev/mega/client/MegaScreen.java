package dev.mega.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.function.IntSupplier;

/** In-game settings panel (default key: 0). Saves on close. */
final class MegaScreen extends Screen {
    private static final int BTN_H = 20, GAP = 3;
    /** Button width: 150, or narrower so three columns always fit the window. */
    private static int BTN_W = 150;
    private final Screen parent;
    private TextFieldWidget ignoredField;

    MegaScreen(Screen parent) {
        super(Text.literal("M.E.G.A Client Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MegaConfig c = MegaClient.config;
        // 3 columns so everything fits even at GUI scale 4
        BTN_W = Math.max(90, Math.min(150, (width - 12 - 2 * GAP) / 3));
        int totalW = 3 * BTN_W + 2 * GAP;
        int[] col = {width / 2 - totalW / 2, width / 2 - totalW / 2 + BTN_W + GAP, width / 2 - totalW / 2 + 2 * (BTN_W + GAP)};
        int y = 22;

        // Toggles
        Object[][] toggles = {
            {"HUD", (Supplier<Boolean>) () -> c.hudEnabled, (Consumer<Boolean>) v -> c.hudEnabled = v},
            {"Player warning", (Supplier<Boolean>) () -> c.playerWarning, (Consumer<Boolean>) v -> c.playerWarning = v},
            {"Player list", (Supplier<Boolean>) () -> c.playerList, (Consumer<Boolean>) v -> c.playerList = v},
            {"Player lines", (Supplier<Boolean>) () -> c.tracePlayers, (Consumer<Boolean>) v -> c.tracePlayers = v},
            {"Invisible players", (Supplier<Boolean>) () -> c.showInvisiblePlayers, (Consumer<Boolean>) v -> c.showInvisiblePlayers = v},
            {"Tracers", (Supplier<Boolean>) () -> c.tracers, (Consumer<Boolean>) v -> c.tracers = v},
            {"Boxes", (Supplier<Boolean>) () -> c.boxes, (Consumer<Boolean>) v -> c.boxes = v},
            {"Ores (cyan)", (Supplier<Boolean>) () -> c.ores, (Consumer<Boolean>) v -> c.ores = v},
            {"Spawners (purple)", (Supplier<Boolean>) () -> c.spawners, (Consumer<Boolean>) v -> c.spawners = v},
            {"Valuables (blue)", (Supplier<Boolean>) () -> c.valuables, (Consumer<Boolean>) v -> c.valuables = v},
            {"Shulkers (green)", (Supplier<Boolean>) () -> c.shulkers, (Consumer<Boolean>) v -> c.shulkers = v},
            {"Storage (red)", (Supplier<Boolean>) () -> c.storage, (Consumer<Boolean>) v -> c.storage = v},
            {"Farm alert", (Supplier<Boolean>) () -> c.farmAlert, (Consumer<Boolean>) v -> c.farmAlert = v},
            {"Outline sneaky players", (Supplier<Boolean>) () -> c.outlineSneaky, (Consumer<Boolean>) v -> c.outlineSneaky = v},
            {"M.E.G.A X-ray", (Supplier<Boolean>) () -> c.xray, (Consumer<Boolean>) v -> { c.xray = v; MegaClient.applyConfig(); }},
            {"X-ray outlines", (Supplier<Boolean>) () -> c.xrayGhost, (Consumer<Boolean>) v -> c.xrayGhost = v},
            {"X-ray lava & water", (Supplier<Boolean>) () -> c.xrayFluids, (Consumer<Boolean>) v -> c.xrayFluids = v},
            {"Blocks seen panel", (Supplier<Boolean>) () -> c.blockCountPanel, (Consumer<Boolean>) v -> c.blockCountPanel = v},
        };
        for (int i = 0; i < toggles.length; i++) {
            @SuppressWarnings("unchecked") Supplier<Boolean> get = (Supplier<Boolean>) toggles[i][1];
            @SuppressWarnings("unchecked") Consumer<Boolean> set = (Consumer<Boolean>) toggles[i][2];
            String name = (String) toggles[i][0];
            int bx = col[i % 3];
            int by = y + (i / 3) * (BTN_H + GAP);
            addDrawableChild(ButtonWidget.builder(toggleText(name, get.get()), b -> {
                set.accept(!get.get());
                b.setMessage(toggleText(name, get.get()));
            }).dimensions(bx, by, BTN_W, BTN_H).build());
        }
        y += ((toggles.length + 2) / 3) * (BTN_H + GAP) + 2;

        // Sliders
        addDrawableChild(new IntSlider(col[0], y, "Block range", 8, 1250, () -> c.maxDistance, v -> c.maxDistance = v));
        addDrawableChild(new IntSlider(col[1], y, "Ore range", 8, 1250, () -> c.oreRange, v -> c.oreRange = v));
        addDrawableChild(new IntSlider(col[2], y, "Player range", 8, 1250, () -> c.playerRange, v -> c.playerRange = v));
        y += BTN_H + GAP + 10;
        addDrawableChild(new IntSlider(col[0], y, "Max tracers", 0, 50, () -> c.maxTracers, v -> c.maxTracers = v));

        // Ignored players (comma separated), next to Max tracers
        ignoredField = new TextFieldWidget(textRenderer, col[1], y, BTN_W * 2 + GAP, BTN_H, Text.literal("Ignored players"));
        ignoredField.setMaxLength(512);
        ignoredField.setText(String.join(", ", c.ignoredPlayers));
        ignoredField.setPlaceholder(Text.literal("Ignored players, comma separated"));
        addDrawableChild(ignoredField);
        y += BTN_H + GAP + 1;

        int half = (totalW - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("§eWhat to detect..."), b -> {
            saveIgnored();
            client.setScreen(new MegaTargetsScreen(this));
        }).dimensions(col[0], y, half, BTN_H).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(col[0] + half + GAP, y, half, BTN_H).build());
    }

    private static Text toggleText(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFD400);
        if (ignoredField != null) {
            ctx.drawTextWithShadow(textRenderer, "Ignored players (never detected):",
                ignoredField.getX(), ignoredField.getY() - 11, 0xFFAAAAAA);
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
        List<String> names = new ArrayList<>();
        for (String n : ignoredField.getText().split(",")) {
            String t = n.trim();
            if (!t.isEmpty()) names.add(t);
        }
        MegaClient.config.ignoredPlayers = names;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Slider for a whole-number setting. */
    private static final class IntSlider extends SliderWidget {
        private final String name;
        private final int min, max;
        private final IntConsumer set;

        IntSlider(int x, int y, String name, int min, int max, IntSupplier get, IntConsumer set) {
            super(x, y, BTN_W, BTN_H, Text.empty(), (get.getAsInt() - min) / (double) (max - min));
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
