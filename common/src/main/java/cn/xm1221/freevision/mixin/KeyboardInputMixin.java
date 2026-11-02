package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes player locomotion while sculpting, so WASD belongs to the camera and the body stays put.
 *
 * <p>The raw key state is still readable through {@code Options.keyUp} etc., which is exactly what
 * the sculpt movement loop uses - only the <i>player's</i> copy of the input is cleared.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void freevision$freezePlayerInput(boolean slowDown, float slowDownFactor, CallbackInfo callbackInfo) {
        if (!VisionController.get().consumesPlayerInput()) {
            return;
        }

        Input input = (Input) (Object) this;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }
}
