package cn.xm1221.freevision.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * All key mappings owned by FreeVision.
 *
 * <p>They are ordinary {@link KeyMapping}s, which means the player rebinds them in
 * {@code Options -> Controls -> FreeVision} and the binding is stored by vanilla in
 * {@code options.txt} like every other key. Nothing here is hard-coded at runtime.
 */
public final class VisionKeybinds {
    public static final String CATEGORY = "key.categories.freevision";

    /** F8 by default: tap to toggle the saved perspective, hold to sculpt it. */
    public static KeyMapping vision;

    /** F9 by default: switch between pose slots (multiple saved perspectives). */
    public static KeyMapping nextSlot;

    /** Comma: roll the camera left while sculpting. */
    public static KeyMapping rollLeft;

    /** Period: roll the camera right while sculpting. */
    public static KeyMapping rollRight;

    /** R: put the rig back at the player's eyes while sculpting. */
    public static KeyMapping reset;

    private static boolean created;

    private VisionKeybinds() {
    }

    /** Builds the mappings once; later calls are no-ops. */
    public static void create() {
        if (created) {
            return;
        }

        created = true;
        vision = make("freevision.key.vision", GLFW.GLFW_KEY_F8);
        nextSlot = make("freevision.key.next_slot", GLFW.GLFW_KEY_F9);
        rollLeft = make("freevision.key.roll_left", GLFW.GLFW_KEY_COMMA);
        rollRight = make("freevision.key.roll_right", GLFW.GLFW_KEY_PERIOD);
        reset = make("freevision.key.reset", GLFW.GLFW_KEY_R);
    }

    public static KeyMapping[] all() {
        return new KeyMapping[] { vision, nextSlot, rollLeft, rollRight, reset };
    }

    private static KeyMapping make(String translationKey, int keyCode) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, keyCode, CATEGORY);
    }
}
