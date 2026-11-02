package cn.xm1221.freevision.forge;

import cn.xm1221.freevision.client.FreeVisionClient;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only half of the Forge entrypoint. Only ever loaded through {@code DistExecutor}, so this
 * class and its imports never resolve on a dedicated server.
 */
public final class FreeVisionForgeClient {
    public FreeVisionForgeClient() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onRegisterKeyMappings);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        FreeVisionClient.init(event::register);
    }
}
