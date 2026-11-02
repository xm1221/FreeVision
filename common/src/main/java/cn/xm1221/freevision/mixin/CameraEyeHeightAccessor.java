package cn.xm1221.freevision.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the camera's <b>smoothed</b> eye height.
 *
 * <p>Vanilla lerps between the previous and current eye height over a few ticks, which is what makes
 * crouching feel soft in first person. The entity's own {@code getEyeHeight()} is already at the new
 * value, so anchoring the rig to that would make the custom perspective snap 0.3 blocks on every
 * crouch. Reading the camera's pair keeps the custom view on exactly the same curve as vanilla.
 */
@Mixin(Camera.class)
public interface CameraEyeHeightAccessor {
    @Accessor("eyeHeight")
    float freevision$eyeHeight();

    @Accessor("eyeHeightOld")
    float freevision$eyeHeightOld();
}
