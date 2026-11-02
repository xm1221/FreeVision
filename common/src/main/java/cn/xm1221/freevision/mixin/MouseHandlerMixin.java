package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mouse wheel dollies the rig in and out while sculpting; the hotbar keeps it otherwise.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void freevision$sculptScroll(long window, double xOffset, double yOffset, CallbackInfo callbackInfo) {
        if (VisionController.get().handleScroll(yOffset)) {
            callbackInfo.cancel();
        }
    }
}
