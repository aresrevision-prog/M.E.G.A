package dev.mega.client;

import dev.mega.client.MegaClient.Category;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** "What to detect" page: one clickable on/off button per target type, coloured by category. */
final class MegaTargetsScreen extends Screen {
    private static final int COLS = 3, BTN_H = 20, GAP = 2;
    private int BTN_W = 150;
    private final Screen parent;
    private final List<Runnable> refreshers = new ArrayList<>();

    MegaTargetsScreen(Screen parent) {
        super(Text.literal("What to detect"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshers.clear();
        List<String> off = MegaClient.config.disabledTargets;
        List<Object[]> options = MegaClient.targetOptions();
        BTN_W = Math.max(90, Math.min(150, (width - 12 - (COLS - 1) * GAP) / COLS));
        int totalW = COLS * BTN_W + (COLS - 1) * GAP;
        int x0 = width / 2 - totalW / 2;
        int y0 = 22;

        for (int i = 0; i < options.size(); i++) {
            String key = (String) options.get(i)[0];
            String label = (String) options.get(i)[1];
            Category cat = (Category) options.get(i)[2];
            int x = x0 + (i % COLS) * (BTN_W + GAP);
            int y = y0 + (i / COLS) * (BTN_H + GAP);
            ButtonWidget b = ButtonWidget.builder(text(label, cat, !off.contains(key)), btn -> {
                if (!off.remove(key)) off.add(key);
                btn.setMessage(text(label, cat, !off.contains(key)));
            }).dimensions(x, y, BTN_W, BTN_H).build();
            refreshers.add(() -> b.setMessage(text(label, cat, !off.contains(key))));
            addDrawableChild(b);
        }

        int y = y0 + ((options.size() + COLS - 1) / COLS) * (BTN_H + GAP) + 8;
        int half = (totalW - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("§aAll on"), b -> {
            off.clear();
            refreshers.forEach(Runnable::run);
        }).dimensions(x0, y, half, BTN_H).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("§cAll off"), b -> {
            off.clear();
            for (Object[] o : options) off.add((String) o[0]);
            refreshers.forEach(Runnable::run);
        }).dimensions(x0 + half + GAP, y, half, BTN_H).build());
        y += BTN_H + GAP;
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(width / 2 - 100, y, 200, BTN_H).build());
    }

    /** "■ Chest: ON" with the square in the category colour. */
    private static Text text(String label, Category cat, boolean on) {
        return Text.literal("■ ").withColor(cat.color & 0xFFFFFF)
            .append(Text.literal(label + ": " + (on ? "§aON" : "§cOFF")));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFD400);
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
