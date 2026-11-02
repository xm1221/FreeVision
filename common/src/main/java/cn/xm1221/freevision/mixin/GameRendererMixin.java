package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import cn.xm1221.freevision.camera.VisionPicker;
import cn.xm1221.freevision.config.VisionConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Three render-side adjustments that only make sense once the camera is off the player's head:
 * crosshair re-targeting, hiding the first-person hand/scope, and dropping view bobbing.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    /** Vanilla picks from the player's eyes; re-pick from the custom camera afterwards. */
    @Inject(method = "pick", at = @At("TAIL"))
    private void freevision$repick(float partialTick, CallbackInfo callbackInfo) {
        VisionPicker.repick(this.minecraft, partialTick);
    }

    /**
     * The hand model is welded to the player's eyes. With a detached camera it would float in
     * mid-air, so it is skipped - together with the first-person screen effects that live in the
     * same branch (suffocation overlay, spyglass, pumpkin blur).
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void freevision$hideFirstPersonHand(PoseStack poseStack, Camera camera, float partialTick, CallbackInfo callbackInfo) {
        if (VisionController.get().isOverriding()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void freevision$disableBobbing(PoseStack poseStack, float partialTick, CallbackInfo callbackInfo) {
        if (VisionController.get().isOverriding() && VisionConfig.get().settings.disableViewBobbing) {
            callbackInfo.cancel();
        }
    }
}
