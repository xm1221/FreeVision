package cn.xm1221.freevision.forge;

import cn.xm1221.freevision.FreeVision;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entrypoint.
 *
 * <p>Forge 1.20.1 has no {@code @Mod(dist=...)}; a client-only mod is expressed by keeping every
 * client class behind {@link DistExecutor}, so the dedicated server never links them, plus
 * {@code displayTest = "IGNORE_ALL_VERSION"} in {@code mods.toml}.
 *
 * <p>The event bus arrives through constructor injection. {@code FMLJavaModLoadingContext.get()} is
 * deprecated for removal since 1.21.1 (Forge 47.4 warns at compile time), and
 * {@code FMLModContainer} already prefers a one-argument constructor taking the context, so this is
 * both the current and the forward-compatible way to get the mod event bus.
 */
@Mod(FreeVision.MOD_ID)
public final class FreeVisionForge {
    public FreeVisionForge(FMLJavaModLoadingContext context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FreeVisionForgeClient.install(context));
    }
}
