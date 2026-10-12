package dev.mega.client.mixin;

import dev.mega.client.MegaHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the M.E.G.A HUD on top of the normal HUD (replaces Fabric API's HudElementRegistry). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void mega$hud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MegaHud.render(MinecraftClient.getInstance(), context);
    }
}
