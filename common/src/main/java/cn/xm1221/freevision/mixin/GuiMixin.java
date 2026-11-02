package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import cn.xm1221.freevision.client.VisionHud;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the overlay and keeps first-person-only decorations off the screen while the custom
 * perspective is applied (pumpkin blur, spyglass frame).
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void freevision$renderOverlay(GuiGraphics graphics, float partialTick, CallbackInfo callbackInfo) {
        VisionHud.render(graphics, partialTick);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;")
    )
    private CameraType freevision$cameraTypeForOverlays(Options options) {
        if (VisionController.get().isOverriding()) {
            return CameraType.THIRD_PERSON_BACK;
        }

        return options.getCameraType();
    }
}
