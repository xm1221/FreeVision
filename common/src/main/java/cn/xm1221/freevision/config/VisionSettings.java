package cn.xm1221.freevision.config;

/**
 * User tunables, persisted verbatim to {@code config/freevision.json}.
 *
 * <p>Everything here is hot-reloadable at runtime (the config screen is the JSON file itself);
 * the controller reads the live values each tick instead of caching them.
 */
public final class VisionSettings {
    /** How long the key must be held before it counts as "hold to sculpt" instead of a tap. */
    public int holdThresholdMs = 220;

    /** Camera translation speed while sculpting, in blocks per client tick (20/s). */
    public double sculptSpeed = 0.22;

    /** Maximum distance the rig may travel away from the player's eye, in blocks. */
    public double maxOffset = 16.0;

    /** Milliseconds for the in/out camera blend. */
    public int transitionMs = 220;

    /** Smoothly fly between the vanilla camera and the custom one. */
    public boolean smoothTransitions = true;

    /** Pull the custom camera out of walls/solids like vanilla third person does. */
    public boolean avoidClipping = true;

    /** Re-target the crosshair from the custom camera instead of the player's eyes. */
    public boolean cameraPicking = true;

    /** Suppress view bobbing while the custom perspective is applied. */
    public boolean disableViewBobbing = true;

    /** Draw the status / sculpt overlay. */
    public boolean showHud = true;

    /** Play a soft click when a pose is saved or the view is toggled. */
    public boolean playSounds = true;

    /** Hold the sprint key (Ctrl) while sculpting for fine 1/5 speed movement. */
    public boolean precisionModifier = true;

    /** How many pose slots the player may keep (saved poses beyond the last are dropped). */
    public int maxSlots = 3;
}
