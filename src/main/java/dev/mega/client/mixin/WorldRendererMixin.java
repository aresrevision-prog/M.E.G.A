package dev.mega.client.mixin;

import dev.mega.client.MegaTracers;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws tracers, boxes, X-ray outlines and see-through lava at the end of the main world pass: the same place
 * Fabric API fires WorldRenderEvents.END_MAIN in 1.21.11 (the frame-graph lambda inside WorldRenderer.render).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "method_62214", at = @At(value = "INVOKE:LAST",
        target = "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;draw()V"))
    private void mega$world(CallbackInfo ci) {
        MegaTracers.renderWorld();
    }
}
