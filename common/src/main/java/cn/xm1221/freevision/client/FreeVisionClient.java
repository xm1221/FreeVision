package cn.xm1221.freevision.client;

import cn.xm1221.freevision.FreeVision;
import cn.xm1221.freevision.config.VisionConfig;
import dev.architectury.event.events.client.ClientGuiEvent;
import net.minecraft.client.KeyMapping;

/**
 * Common client bootstrap. The two things a platform module has to provide are a way to register key
 * mappings and the client lifecycle entrypoint; everything else is registered here.
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

        KeyMapping[] mappings = VisionKeybinds.all();

        for (KeyMapping mapping : mappings) {
            registrar.register(mapping);
        }

        // Cross-platform HUD hook. This must NOT be an injection into Gui#render: Forge swaps the GUI
        // for ForgeGui, which overrides render() and never calls the Gui version, so a Gui mixin is
        // silently dead on Forge. Architectury routes this event to RenderGuiEvent.Post there and to
        // Gui#render on Fabric.
        ClientGuiEvent.RENDER_HUD.register((graphics, partialTick) -> VisionHud.render(graphics, partialTick));

        warnAboutDuplicateBindings(mappings);
        FreeVision.LOG.info("FreeVision ready: {} key mappings registered", mappings.length);
    }

    /**
     * Two actions on the same key is a silent failure: {@code KeyMapping}'s key lookup is rebuilt
     * from {@code Options.keyMappings} and the later entry wins, so the earlier action simply never
     * fires. That is exactly how a stale {@code next_slot = F9} left over in {@code options.txt}
     * kept swallowing the adjust key. This only reports it - the fix is to rebind one of them.
     */
    private static void warnAboutDuplicateBindings(KeyMapping[] mappings) {
        for (int first = 0; first < mappings.length; first++) {
            for (int second = first + 1; second < mappings.length; second++) {
                KeyMapping a = mappings[first];
                KeyMapping b = mappings[second];

                if (a != null && b != null && a.same(b)) {
                    FreeVision.LOG.warn("FreeVision: '{}' and '{}' are both bound to {} - only the latter will work",
                            a.getName(), b.getName(), a.getTranslatedKeyMessage().getString());
                }
            }
        }
    }
}
