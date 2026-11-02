package cn.xm1221.freevision.config;

/**
 * User tunables, persisted verbatim to {@code config/freevision.json}.
 *
 * <p>Values are read at startup; editing the file while the game is running does not take effect
 * until the next launch. Every mutation the mod itself makes (saving, slot cycling, lock toggling)
 * goes through {@link VisionConfig#save()}, so the file is always current on disk.
 */
public final class VisionSettings {
    /** Camera translation speed while adjusting, in blocks per client tick (20/s). */
    public double sculptSpeed = 0.22D;

    /** Maximum distance the rig may travel away from the player's eye, in blocks. */
    public double maxOffset = 16.0D;

    /** Milliseconds for the in/out camera blend. Set to 0 for an instant cut. */
    public int transitionMs = 220;

    /** Smoothly fly between the vanilla camera and the custom one. */
    public boolean smoothTransitions = true;

    /** Pull the custom camera out of walls/solids like vanilla third person does. */
    public boolean avoidClipping = true;

    /** Re-target the crosshair from the custom camera instead of the player's eyes. */
    public boolean cameraPicking = true;

    /** Suppress view bobbing while the custom perspective is applied. */
    public boolean disableViewBobbing = true;

    /** Master switch for every overlay. */
    public boolean showHud = true;

    /** The adjusting panel (pose numbers and key hints). */
    public boolean showPanel = true;

    /** Short status toasts ("perspective saved", "view lock on", ...). */
    public boolean showToasts = true;

    /** Play a soft click when a pose is saved or the view is toggled. */
    public boolean playSounds = true;

    /** Hold the sprint key while adjusting for fine 1/5 speed movement. */
    public boolean precisionModifier = true;

    /** How many pose slots the player may keep. */
    public int maxSlots = 3;

    /**
     * Falls every field back into a sane range. Called on load so a hand-edited or corrupted file
     * can never put the camera at infinity, at a million blocks away, or at NaN - any of which would
     * make the per-frame clip ray traversal stall the client.
     *
     * @return {@code true} when something had to be corrected
     */
    public boolean sanitize() {
        boolean changed = !Double.isFinite(this.sculptSpeed) || !Double.isFinite(this.maxOffset);

        this.sculptSpeed = Double.isFinite(this.sculptSpeed) ? Math.max(0.001D, Math.min(2.0D, this.sculptSpeed)) : 0.22D;
        this.maxOffset = Double.isFinite(this.maxOffset) ? Math.max(1.0D, Math.min(64.0D, this.maxOffset)) : 16.0D;
        this.transitionMs = Math.max(0, Math.min(2000, this.transitionMs));
        this.maxSlots = Math.max(1, Math.min(16, this.maxSlots));

        return changed;
    }
}
