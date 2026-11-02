package cn.xm1221.freevision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared identity of the mod. Kept free of any Minecraft class reference so that
 * both platform entrypoints can touch it without pulling client code in.
 */
public final class FreeVision {
    public static final String MOD_ID = "freevision";
    public static final String MOD_NAME = "FreeVision";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private FreeVision() {
    }
}
