package dev.mega.client.mixin;

import dev.mega.client.MegaClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the M.E.G.A key bindings to Options → Controls before saved keys load (replaces Fabric API's KeyBindingHelper). */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @Mutable @Final @Shadow public KeyBinding[] allKeys;

    @Inject(method = "load", at = @At("HEAD"))
    private void mega$addKeys(CallbackInfo ci) {
        allKeys = MegaClient.addKeys(allKeys);
    }
}
