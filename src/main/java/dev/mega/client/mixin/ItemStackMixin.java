package dev.mega.client.mixin;

import dev.mega.client.MegaShulkers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Shulker box tooltip: full contents (the game itself only lists 5). */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void mega$shulkerTooltip(Item.TooltipContext context, PlayerEntity player, TooltipType type,
                                     CallbackInfoReturnable<List<Text>> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (!MegaShulkers.isShulker(self)) return;
        List<Text> lines = new ArrayList<>(cir.getReturnValue());
        MegaShulkers.addTooltip(self, lines);
        cir.setReturnValue(lines);
    }
}
