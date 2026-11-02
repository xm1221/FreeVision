package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Esc means "throw this pose away" while a custom perspective is being edited, instead of opening
 * the pause menu. In every other state Esc is untouched.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void freevision$cancelWithEscape(long window, int key, int scancode, int action, int modifiers, CallbackInfo callbackInfo) {
        if (action != GLFW.GLFW_PRESS || key != GLFW.GLFW_KEY_ESCAPE) {
            return;
        }

        if (VisionController.get().cancel()) {
            callbackInfo.cancel();
        }
    }
}
