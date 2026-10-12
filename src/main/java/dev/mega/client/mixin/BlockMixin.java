package dev.mega.client.mixin;

import dev.mega.client.MegaXray;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-ray hook: both vanilla and Fabric's Indigo renderer ask this method whether a block face should be drawn. */
@Mixin(Block.class)
public abstract class BlockMixin {
    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0)
    private static void mega$xray(BlockState state, BlockState otherState, Direction side,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (MegaXray.active()) cir.setReturnValue(MegaXray.isVisible(state));
    }
}
