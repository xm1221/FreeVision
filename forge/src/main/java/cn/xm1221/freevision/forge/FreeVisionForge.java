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
 */
@Mod(FreeVision.MOD_ID)
public final class FreeVisionForge {
    public FreeVisionForge() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> FreeVisionForgeClient::new);
    }
}
