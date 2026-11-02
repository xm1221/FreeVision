package cn.xm1221.freevision.camera;

/**
 * The lifecycle of the custom perspective.
 *
 * <p>Editing is an explicit <b>mode</b>, not a hold: pressing the edit key enters
 * {@link #SCULPTING}, pressing it again saves and drops you into {@link #ACTIVE}, Esc throws the
 * edits away. Nothing depends on holding a key down, so the player can look at the HUD, take their
 * time, or alt-tab without losing the pose.
 *
 * <pre>
 *  VANILLA ──(编辑键)──▶ SCULPTING ──(编辑键)──▶ ACTIVE ──(切换键)──▶ LEAVING ──▶ VANILLA
 *     ▲                                                ▲
 *     └────────────────(Esc 放弃)──────────────────────┘
 * </pre>
 */
public enum VisionState {
    /** Nothing of ours touches the camera. */
    VANILLA,
    /** Edit mode: the camera is driven by the mouse, movement keys and the wheel. */
    SCULPTING,
    /** The saved pose is applied and follows the player. */
    ACTIVE,
    /** Transitioning back to a vanilla camera; drops out once the blend completes. */
    LEAVING;

    public boolean isSculpting() {
        return this == SCULPTING;
    }

    public boolean isOverriding() {
        return this != VANILLA;
    }

    public boolean isApplied() {
        return this == ACTIVE || this == SCULPTING;
    }
}
