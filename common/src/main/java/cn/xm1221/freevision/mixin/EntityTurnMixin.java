package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the player's mouse look.
 *
 * <p>{@code MouseHandler.turnPlayer} already applies sensitivity, invert-Y, smooth-camera and the
 * scoped/slow modifiers, then hands the finished deltas to {@code Entity#turn}. Hooking there means
 * the sculpting camera inherits all of that for free and stays consistent with the player's own
 * settings; we simply swallow the turn so the body does not move while the camera does.
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void freevision$captureMouseLook(double yawDelta, double pitchDelta, CallbackInfo callbackInfo) {
        if ((Object) this != Minecraft.getInstance().player) {
            return;
        }

        if (VisionController.get().handleMouseTurn(yawDelta, pitchDelta)) {
            callbackInfo.cancel();
        }
    }
}
