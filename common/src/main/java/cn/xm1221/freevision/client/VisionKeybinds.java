package cn.xm1221.freevision.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * All key mappings owned by FreeVision.
 *
 * <p>They are ordinary {@link KeyMapping}s, so they are rebindable in
 * {@code Options -> Controls -> FreeVision}, the binding lands in {@code options.txt}, and every
 * hint the mod prints names the key the player actually has (via
 * {@link KeyMapping#getTranslatedKeyMessage()}) instead of hard-coding "F8".
 *
 * <p>Nothing here depends on holding a key down to change modes; the only held keys are the ones
 * where continuous motion is the point (move, look, roll).
 */
public final class VisionKeybinds {
    public static final String CATEGORY = "key.categories.freevision";

    /** F8: switch the saved perspective on and off. */
    public static KeyMapping vision;

    /** F9: enter adjust mode; press again to save. */
    public static KeyMapping edit;

    /** V: flip the rig between tracking the player's look and holding a world direction. */
    public static KeyMapping lock;

    /** Backspace: throw the current adjustment away. */
    public static KeyMapping discard;

    /** F10: cycle between saved pose slots. */
    public static KeyMapping nextSlot;

    /** Arrow keys: turn the camera without a mouse (keyboard-only / trackpad players). */
    public static KeyMapping lookUp;
    public static KeyMapping lookDown;
    public static KeyMapping lookLeft;
    public static KeyMapping lookRight;

    /** Bracket keys: dolly the camera in and out without a scroll wheel. */
    public static KeyMapping dollyIn;
    public static KeyMapping dollyOut;

    /** Comma / period: roll the camera while adjusting. */
    public static KeyMapping rollLeft;
    public static KeyMapping rollRight;

    /** R: put the rig back at the player's eyes while adjusting. */
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
        edit = make("freevision.key.edit", GLFW.GLFW_KEY_F9);
        lock = make("freevision.key.lock", GLFW.GLFW_KEY_V);
        discard = make("freevision.key.discard", GLFW.GLFW_KEY_BACKSPACE);
        nextSlot = make("freevision.key.next_slot", GLFW.GLFW_KEY_F10);
        lookUp = make("freevision.key.look_up", GLFW.GLFW_KEY_UP);
        lookDown = make("freevision.key.look_down", GLFW.GLFW_KEY_DOWN);
        lookLeft = make("freevision.key.look_left", GLFW.GLFW_KEY_LEFT);
        lookRight = make("freevision.key.look_right", GLFW.GLFW_KEY_RIGHT);
        dollyIn = make("freevision.key.dolly_in", GLFW.GLFW_KEY_LEFT_BRACKET);
        dollyOut = make("freevision.key.dolly_out", GLFW.GLFW_KEY_RIGHT_BRACKET);
        rollLeft = make("freevision.key.roll_left", GLFW.GLFW_KEY_COMMA);
        rollRight = make("freevision.key.roll_right", GLFW.GLFW_KEY_PERIOD);
        reset = make("freevision.key.reset", GLFW.GLFW_KEY_R);
    }

    public static KeyMapping[] all() {
        return new KeyMapping[] {
                vision, edit, lock, discard, nextSlot,
                lookUp, lookDown, lookLeft, lookRight,
                dollyIn, dollyOut, rollLeft, rollRight, reset
        };
    }

    private static KeyMapping make(String translationKey, int keyCode) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, keyCode, CATEGORY);
    }
}
