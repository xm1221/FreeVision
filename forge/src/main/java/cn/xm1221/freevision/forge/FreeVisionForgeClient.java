package cn.xm1221.freevision.forge;

import cn.xm1221.freevision.client.FreeVisionClient;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only half of the Forge entrypoint. Only ever loaded through {@code DistExecutor}, so this
 * class and its imports never resolve on a dedicated server.
 */
public final class FreeVisionForgeClient {
    private FreeVisionForgeClient() {
    }

    /** Hooks key registration on the mod event bus handed over by the mod constructor. */
    public static void install(FMLJavaModLoadingContext context) {
        context.getModEventBus().addListener(FreeVisionForgeClient::onRegisterKeyMappings);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        FreeVisionClient.init(event::register);
    }
}
