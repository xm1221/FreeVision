package cn.xm1221.freevision.client;

import net.minecraft.client.KeyMapping;

/** Platform hook for registering key mappings at the only moment each loader allows. */
@FunctionalInterface
public interface KeybindRegistrar {
    void register(KeyMapping mapping);
}
