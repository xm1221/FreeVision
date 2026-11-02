package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.VisionController;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the controller once per client tick and keeps vanilla gameplay keybinds quiet while the
 * camera is being sculpted (so holding a movement key never drops an item or swings a sword).
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

        // Clicks buffer inside KeyMapping; drain them or they fire the moment sculpting ends.
        freevision$drain(options.keyAttack);
        freevision$drain(options.keyUse);
        freevision$drain(options.keyPickItem);
        freevision$drain(options.keyDrop);
        freevision$drain(options.keySwapOffhand);
        freevision$drain(options.keyInventory);
        freevision$drain(options.keyChat);
        freevision$drain(options.keyCommand);
        freevision$drain(options.keyAdvancements);
        freevision$drain(options.keySocialInteractions);
        freevision$drain(options.keyTogglePerspective);
        freevision$drain(options.keySmoothCamera);

        for (KeyMapping slot : options.keyHotbarSlots) {
            freevision$drain(slot);
        }

        callbackInfo.cancel();
    }

    @Unique
    private static void freevision$drain(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }

        while (mapping.consumeClick()) {
            // discard
        }
    }
}
