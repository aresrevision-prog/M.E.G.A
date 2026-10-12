package dev.mega.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Camera position for world drawing. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("pos")
    Vec3d mega$getPos();
}
