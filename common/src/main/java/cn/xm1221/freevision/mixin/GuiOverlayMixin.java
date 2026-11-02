package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the first-person-only screen effects while the custom perspective is applied: the
 * pumpkin blur, the spyglass frame and the powder-snow/frost outline all draw straight onto the
 * screen as if the camera were inside the player's head.
 *
 * <p><b>Why {@code @Inject} and not {@code @Redirect}.</b> MixinExtras 0.5.4 crashes the game on any
 * {@code @Redirect} whose {@code at} element is stored as a list (which is the normal shape javac
 * emits for {@code at = @At(...)}): its {@code FactoryRedirectWrapperMixinTransformer} casts the
 * value straight to {@code AnnotationNode} and throws {@code ClassCastException} while transforming
 * the target class. Shipping a mod that trips that means "install and the game refuses to start" on
 * every pack bundling that version. Keeping our mixins free of {@code @Redirect} (and of every other
 * MixinExtras annotation) removes the dependency on that library entirely.
 *
 * <p>Only meaningful on Fabric: Forge replaces the GUI with {@code ForgeGui} and draws these effects
 * through its own overlay system, so this mixin is inert there.
 */
@Mixin(Gui.class)
public abstract class GuiOverlayMixin {
    @Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void freevision$hideSpyglassOverlay(GuiGraphics graphics, float scale, CallbackInfo callbackInfo) {
        if (VisionController.get().isOverriding()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void freevision$hideTextureOverlay(GuiGraphics graphics, ResourceLocation texture, float alpha,
            CallbackInfo callbackInfo) {
        if (VisionController.get().isOverriding()) {
            callbackInfo.cancel();
        }
    }
}
