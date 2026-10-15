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
    private static final String[] TABS = {"Colours", "Display", "Players", "Waypoints", "Map"};
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
        int tabW = (w - (TABS.length - 1) * GAP) / TABS.length;
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
            // One row per colour: [■ Ores  ON] [Edit 5/5 ›]. Two columns when one column won't fit the screen.
            Category[] cats = Category.values();
            boolean two = 48 + cats.length * (BTN_H + GAP) + BTN_H + 12 > height;
            int perCol = two ? (cats.length + 1) / 2 : cats.length;
            int colWidth = two ? colW : w;
            int toggleW = (int) (colWidth * (two ? 0.62 : 0.66));
            int startY = y;
            for (int ci = 0; ci < cats.length; ci++) {
                Category cat = cats[ci];
                int cx = x0 + (ci / perCol) * (colW + GAP);
                y = startY + (ci % perCol) * (BTN_H + GAP);
                addDrawableChild(ButtonWidget.builder(colourText(cat, cat.enabled(c)), b -> {
                    cat.setEnabled(c, !cat.enabled(c));
                    b.setMessage(colourText(cat, cat.enabled(c)));
                }).dimensions(cx, y, toggleW, BTN_H).build());
                List<String> blocks = cat.blocks(c);
                long on = blocks.stream().filter(id -> !c.disabledTargets.contains(id)).count();
                addDrawableChild(ButtonWidget.builder(Text.literal((two ? "" : "Edit  ") + "§7" + on + "/" + blocks.size() + " §f›"),
                    b -> client.setScreen(new MegaBlocksScreen(this, cat)))
                    .dimensions(cx + toggleW + GAP, y, colWidth - toggleW - GAP, BTN_H).build());
            }
            y = startY + perCol * (BTN_H + GAP);
        } else if (tab == 1) {
            // Screen | Lines | X-ray | Range, two per row
            y = toggleGrid(List.of(
                t("HUD", () -> c.hudEnabled, v -> c.hudEnabled = v),
                t("Blocks seen panel", () -> c.blockCountPanel, v -> c.blockCountPanel = v),
                t("Lines", () -> c.tracers, v -> c.tracers = v),
                t("Boxes", () -> c.boxes, v -> c.boxes = v),
                t("Radars", () -> c.radar, v -> c.radar = v)), x0, y, colW);
            addDrawableChild(new IntSlider(x0 + colW + GAP, y - BTN_H - GAP, colW, "Radar range", 16, 1250,
                () -> c.radarRange, v -> c.radarRange = v));
            addDrawableChild(new IntSlider(x0, y, colW, "Lines shown", 0, 50, () -> c.maxTracers, v -> c.maxTracers = v));
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "List rows", 1, 30, () -> c.maxLines, v -> c.maxLines = v));
            y += BTN_H + GAP + 4;
            y = toggleGrid(List.of(
                t("X-ray", () -> c.xray, v -> { c.xray = v; MegaClient.applyConfig(); }),
                t("X-ray outlines", () -> c.xrayGhost, v -> c.xrayGhost = v),
                t("X-ray lava & water", () -> c.xrayFluids, v -> c.xrayFluids = v),
                t("Ores after stashes", () -> c.oresLast, v -> c.oresLast = v),
                t("Hitboxes", () -> c.hitboxes, v -> c.hitboxes = v)), x0, y, colW) + 4;
            addDrawableChild(new IntSlider(x0 + colW + GAP, y - BTN_H - GAP - 4, colW, "Hitbox range", 8, 256,
                () -> c.hitboxRange, v -> c.hitboxRange = v));
            addDrawableChild(new IntSlider(x0, y, colW, "Block range", 8, 1250, () -> c.maxDistance, v -> c.maxDistance = v));
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "Ore range", 8, 1250, () -> c.oreRange, v -> c.oreRange = v));
            y += BTN_H + GAP;
        } else if (tab == 4) {
            // Corner slots + minimap + tracking
            String[] slots = {"minimap", "blocks", "radars", "none"};
            String[] names = {"Minimap", "Blocks seen", "Radars", "Empty"};
            for (int i = 0; i < 2; i++) {
                final int slotNo = i;
                java.util.function.Supplier<String> get = () -> slotNo == 0 ? c.cornerSlot1 : c.cornerSlot2;
                addDrawableChild(ButtonWidget.builder(slotText(slotNo, get.get(), slots, names), b -> {
                    int idx = (java.util.Arrays.asList(slots).indexOf(get.get()) + 1) % slots.length;
                    if (slotNo == 0) c.cornerSlot1 = slots[idx]; else c.cornerSlot2 = slots[idx];
                    b.setMessage(slotText(slotNo, get.get(), slots, names));
                }).dimensions(x0 + i * (colW + GAP), y, colW, BTN_H).build());
            }
            y += BTN_H + GAP;
            addDrawableChild(ButtonWidget.builder(Text.literal("Minimap layer: §e" + MegaMap.LAYERS[c.minimapLayer]), b -> {
                c.minimapLayer = (c.minimapLayer + 1) % 3;
                b.setMessage(Text.literal("Minimap layer: §e" + MegaMap.LAYERS[c.minimapLayer]));
            }).dimensions(x0, y, colW, BTN_H).build());
            addDrawableChild(new IntSlider(x0 + colW + GAP, y, colW, "Minimap size", 48, 200, () -> c.minimapSize, v -> c.minimapSize = v));
            y += BTN_H + GAP + 4;
            y = toggleGrid(List.of(
                t("Stash memory", () -> c.stashMemory, v -> c.stashMemory = v),
                t("Join/leave alerts", () -> c.joinAlerts, v -> c.joinAlerts = v),
                t("Elytra helper", () -> c.elytraHelper, v -> c.elytraHelper = v),
                t("Last seen", () -> c.lastSeen, v -> c.lastSeen = v),
                t("Player trails", () -> c.trails, v -> c.trails = v)), x0, y, colW);
            addDrawableChild(new IntSlider(x0 + colW + GAP, y - BTN_H - GAP, colW, "Trail seconds", 10, 1800,
                () -> c.trailSeconds, v -> c.trailSeconds = v));
            addDrawableChild(ButtonWidget.builder(Text.literal("§eOpen big map (M)"), b -> {
                MegaClient.config.save();
                MegaClient.applyConfig();
                client.setScreen(new MegaMapScreen());
            }).dimensions(x0, y, colW * 2 + GAP, BTN_H).build());
            y += BTN_H + GAP;
        } else if (tab == 3) {
            waypointsTab(c, x0, w);
            box = new int[]{x0 - 6, 1, x0 + w + 6, height - 1};
            contentEnd = height;
            return;
        } else {
            List<Object[]> toggles = List.of(
                t("Player warning", () -> c.playerWarning, v -> c.playerWarning = v),
                t("Player list", () -> c.playerList, v -> c.playerList = v),
                t("Player lines", () -> c.tracePlayers, v -> c.tracePlayers = v),
                t("Invisible players", () -> c.showInvisiblePlayers, v -> c.showInvisiblePlayers = v),
                t("Outline sneaky players", () -> c.outlineSneaky, v -> c.outlineSneaky = v),
                t("Gear check", () -> c.gearCheck, v -> c.gearCheck = v),
                t("Danger alert", () -> c.dangerAlert, v -> c.dangerAlert = v),
                t("Danger sound", () -> c.dangerSound, v -> c.dangerSound = v));
            y = toggleGrid(toggles, x0, y, colW);
            addDrawableChild(new IntSlider(x0, y, colW, "Player range", 8, 1250,
                () -> c.playerRange, v -> c.playerRange = v));
            y += BTN_H + GAP;
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

    // ---- Waypoints tab ----
    private static int wpPage, selected = -1;
    private TextFieldWidget nameField;
    private String wpStatus = "";

    /** Rows: [● name 123m] (click to select) [X]. Bottom: name box, Add here, Rename, Show/Hide; < Done >. */
    private void waypointsTab(MegaConfig c, int x0, int w) {
        nameField = null;
        if (client.player == null || client.world == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x0, height - BTN_H - 4, w, BTN_H).build());
            wpStatus = "Join a world or server to use waypoints";
            return;
        }
        List<MegaWaypoints.Waypoint> list = MegaWaypoints.list(client);
        if (selected >= list.size()) selected = -1;
        int navY = height - BTN_H - 4, fieldY = navY - BTN_H - 14, top = 48;
        int rows = Math.max(1, (fieldY - GAP - top) / (BTN_H + GAP));
        int pages = Math.max(1, (list.size() + rows - 1) / rows);
        wpPage = Math.clamp(wpPage, 0, pages - 1);
        String dim = MegaWaypoints.dim(client);
        double px = client.player.getX(), py = client.player.getY(), pz = client.player.getZ();
        int xW = 20, mainW = w - xW - GAP;

        for (int i = wpPage * rows, n = 0; i < list.size() && n < rows; i++, n++) {
            MegaWaypoints.Waypoint wp = list.get(i);
            final int idx = i;
            int y = top + n * (BTN_H + GAP);
            String where = dim.equals(wp.dim) ? Math.round(wp.distanceTo(px, py, pz)) + "m"
                : wp.dim.replace("minecraft:", "").replace("the_", "");
            String label = (idx == selected ? "§e» " : "") + (wp.death ? "☠ " : "") + wp.name + "  §7" + where + "  " + wp.x + ", " + wp.y + ", " + wp.z;
            MutableText text = Text.literal("● ").withColor(MegaClient.readable(wp.color) & 0xFFFFFF)
                .append(Text.literal(wp.visible ? label : "§8" + label.replaceAll("§.", "") + " (hidden)"));
            addDrawableChild(ButtonWidget.builder(text, b -> {
                selected = idx == selected ? -1 : idx;
                wpStatus = "";
                clearAndInit();
            }).dimensions(x0, y, mainW, BTN_H).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§cX"), b -> {
                list.remove(wp);
                MegaWaypoints.save();
                selected = -1;
                wpStatus = "Deleted " + wp.name;
                clearAndInit();
            }).dimensions(x0 + w - xW, y, xW, BTN_H).build());
        }

        // Name box + Add here / Rename / Show-Hide
        int bW = 58;
        MegaWaypoints.Waypoint sel = selected >= 0 ? list.get(selected) : null;
        nameField = new TextFieldWidget(textRenderer, x0, fieldY, w - 3 * (bW + GAP), BTN_H, Text.literal("Name"));
        nameField.setMaxLength(40);
        if (sel != null) nameField.setText(sel.name);
        nameField.setPlaceholder(Text.literal(sel != null ? "New name" : "Name (optional)"));
        addDrawableChild(nameField);
        int bx = x0 + w - 3 * bW - 2 * GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("§aAdd here"), b -> {
            MegaWaypoints.Waypoint nw = MegaWaypoints.addHere(client, sel != null ? null : nameField.getText());
            wpStatus = "Added " + nw.name;
            selected = list.indexOf(nw);
            wpPage = Integer.MAX_VALUE;
            clearAndInit();
        }).dimensions(bx, fieldY, bW, BTN_H).build());
        ButtonWidget rename = ButtonWidget.builder(Text.literal("Rename"), b -> {
            if (sel == null || nameField.getText().isBlank()) return;
            sel.name = nameField.getText().trim();
            MegaWaypoints.save();
            wpStatus = "Renamed";
            clearAndInit();
        }).dimensions(bx + bW + GAP, fieldY, bW, BTN_H).build();
        rename.active = sel != null;
        addDrawableChild(rename);
        ButtonWidget hide = ButtonWidget.builder(Text.literal(sel != null && !sel.visible ? "Show" : "Hide"), b -> {
            if (sel == null) return;
            sel.visible = !sel.visible;
            MegaWaypoints.save();
            clearAndInit();
        }).dimensions(bx + 2 * (bW + GAP), fieldY, bW, BTN_H).build();
        hide.active = sel != null;
        addDrawableChild(hide);

        // < [Waypoints ON] [Done] >
        int arrowW = 30, midW = (w - 2 * arrowW - 3 * GAP) / 2;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { wpPage--; clearAndInit(); })
            .dimensions(x0, navY, arrowW, BTN_H).build();
        prev.active = wpPage > 0;
        addDrawableChild(prev);
        addDrawableChild(ButtonWidget.builder(onOff("Show waypoints", c.waypoints), b -> {
            c.waypoints = !c.waypoints;
            b.setMessage(onOff("Show waypoints", c.waypoints));
        }).dimensions(x0 + arrowW + GAP, navY, midW, BTN_H).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(x0 + arrowW + 2 * GAP + midW, navY, midW, BTN_H).build());
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { wpPage++; clearAndInit(); })
            .dimensions(x0 + w - arrowW, navY, arrowW, BTN_H).build();
        next.active = wpPage < pages - 1;
        addDrawableChild(next);
        if (list.isEmpty()) wpStatus = wpStatus.isEmpty() ? "No waypoints yet: press B in game, or Add here" : wpStatus;
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

    private static Text slotText(int slot, String v, String[] slots, String[] names) {
        int i = Math.max(0, java.util.Arrays.asList(slots).indexOf(v));
        return Text.literal("Corner " + (slot + 1) + ": §e" + names[i]);
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
        if (tab == 3) {
            String hint = !wpStatus.isEmpty() ? wpStatus : "Click a waypoint to select it  ·  B adds one where you stand";
            int hy = nameField != null ? nameField.getY() + BTN_H + 3 : height / 2;
            ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(hint, box[2] - box[0] - 8), width / 2, hy, 0xFF9A9A9A);
            return;
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
