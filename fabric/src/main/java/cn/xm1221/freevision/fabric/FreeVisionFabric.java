package cn.xm1221.freevision.fabric;

import cn.xm1221.freevision.client.FreeVisionClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/**
 * Fabric entrypoint. Client only - there is no server component, so nothing is registered in
 * {@code main}.
 */
public final class FreeVisionFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FreeVisionClient.init(KeyBindingHelper::registerKeyBinding);
    }
}
