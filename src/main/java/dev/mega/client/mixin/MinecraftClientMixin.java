package dev.mega.client.mixin;

import dev.mega.client.MegaClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs M.E.G.A once per game tick (replaces Fabric API's ClientTickEvents.END_CLIENT_TICK). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mega$tick(CallbackInfo ci) {
        MegaClient.onTick((MinecraftClient) (Object) this);
    }
}
