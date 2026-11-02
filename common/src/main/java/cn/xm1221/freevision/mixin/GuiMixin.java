package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps first-person-only decorations off the screen while the custom perspective is applied
 * (pumpkin blur, spyglass frame) and keeps the crosshair visible.
 *
 * <p>Both redirects target {@code Gui}, which is <b>Fabric-only territory</b>: Forge swaps the GUI
 * for {@code ForgeGui}, which overrides {@code render} and never calls {@code Gui#render}, so these
 * injections are inert there. The HUD therefore does not live here - it is drawn from Architectury's
 * {@code ClientGuiEvent.RENDER_HUD}, which is routed to {@code RenderGuiEvent.Post} on Forge and to
 * this class's {@code Gui#render} on Fabric.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
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

    @Redirect(
            method = "renderCrosshair",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;")
    )
    private CameraType freevision$cameraTypeForCrosshair(Options options) {
        if (VisionController.get().isOverriding()) {
            return CameraType.FIRST_PERSON;
        }

        return options.getCameraType();
    }
}
