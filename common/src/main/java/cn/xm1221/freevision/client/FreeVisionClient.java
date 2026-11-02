package cn.xm1221.freevision.client;

import cn.xm1221.freevision.config.VisionConfig;
import net.minecraft.client.KeyMapping;

/**
 * Common client bootstrap. The only thing a platform module has to provide is a way to register
 * key mappings, because Fabric and Forge disagree on when that is legal.
 */
public final class FreeVisionClient {
    private static boolean initialized;

    private FreeVisionClient() {
    }

    public static void init(KeybindRegistrar registrar) {
        if (initialized) {
            return;
        }

        initialized = true;
        VisionConfig.load();
        VisionKeybinds.create();

        for (KeyMapping mapping : VisionKeybinds.all()) {
            registrar.register(mapping);
        }
    }
}
