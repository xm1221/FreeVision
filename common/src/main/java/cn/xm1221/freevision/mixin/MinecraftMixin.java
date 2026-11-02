package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the controller once per client tick and keeps gameplay keybinds quiet while the camera is
 * being adjusted.
 *
 * <p>Every mapping is drained, including other mods' entries: clicks buffer inside {@code KeyMapping}
 * and would otherwise all fire on the first tick after adjusting ends (saving hotbars, dropping
 * items, swinging swords). Our own mappings are already consumed earlier in the same tick, so
 * draining them again is a no-op.
 *
 * <p>Esc is deliberately <b>not</b> touched here - it lives in {@code KeyboardHandler}, so the pause
 * menu always works, even in the middle of an adjustment.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void freevision$clientTick(CallbackInfo callbackInfo) {
        VisionController.get().tick((Minecraft) (Object) this);
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void freevision$suppressGameplayKeys(CallbackInfo callbackInfo) {
        if (!VisionController.get().isSculpting()) {
            return;
        }

        Options options = ((Minecraft) (Object) this).options;

        for (var mapping : options.keyMappings) {
            if (mapping == null) {
                continue;
            }

            while (mapping.consumeClick()) {
                // discard
            }
        }

        callbackInfo.cancel();
    }
}
