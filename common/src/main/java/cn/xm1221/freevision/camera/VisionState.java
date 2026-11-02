package cn.xm1221.freevision.camera;

/**
 * The lifecycle of the custom perspective.
 *
 * <pre>
 *  VANILLA --(hold)--> SCULPTING --(release)--> DRAFT --(tap)--> ACTIVE
 *     ^                     |                     |                  |
 *     |                     |                     +---(hold)--> SCULPTING
 *     |                     |                     |
 *     +---------------------+---------------------+--(cancel)-----+
 *                                                     (tap on ACTIVE)
 * </pre>
 */
public enum VisionState {
    /** Nothing of ours touches the camera. */
    VANILLA,
    /** Key held: the camera is being sculpted with mouse + movement keys. */
    SCULPTING,
    /** Key released: the sculpted pose stays on screen and waits for save/cancel. */
    DRAFT,
    /** The saved pose is applied and follows the player. */
    ACTIVE,
    /** Transitioning back to a vanilla camera; drops out once the blend completes. */
    LEAVING;

    public boolean isSculpting() {
        return this == SCULPTING;
    }

    public boolean isOverriding() {
        return this == SCULPTING || this == DRAFT || this == ACTIVE || this == LEAVING;
    }

    public boolean isApplied() {
        return this == ACTIVE || this == SCULPTING || this == DRAFT;
    }
}
