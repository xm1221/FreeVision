package cn.xm1221.freevision.camera;

import cn.xm1221.freevision.FreeVision;
import cn.xm1221.freevision.client.VisionKeybinds;
import cn.xm1221.freevision.config.VisionConfig;
import cn.xm1221.freevision.config.VisionSettings;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * The single owner of the custom-perspective state machine.
 *
 * <p>Everything else in the mod is a thin adapter around this class: mixins forward raw input here,
 * the camera mixin asks it for the pose to render, the HUD asks it what to draw.
 *
 * <p>Interaction model: adjusting is an explicit mode. Press the edit key to enter it, press it
 * again to save, the discard key to throw the edits away. Nothing requires holding a key down to
 * change mode, and Esc is never taken away from the player - it still opens the pause menu.
 *
 * <p>Threading: only ever touched from the client thread (input mixins and the render loop).
 */
public final class VisionController {
    private static final VisionController INSTANCE = new VisionController();

    /** Vanilla applies this factor to raw mouse deltas before storing them as rotation. */
    private static final float VANILLA_TURN_SCALE = 0.15F;

    /** Degrees per tick when turning with the arrow keys. */
    private static final float LOOK_STEP = 1.6F;

    /** Degrees per tick when rolling. */
    private static final float ROLL_STEP = 1.25F;

    private static final long HINT_SHORT_MS = 3500L;
    private static final long HINT_LONG_MS = 6000L;

    /** Debounce for the mode-changing key, in case the OS repeats or the player double-taps. */
    private static final long EDIT_DEBOUNCE_MS = 200L;

    public static VisionController get() {
        return INSTANCE;
    }

    private VisionState state = VisionState.VANILLA;
    private VisionState stateBeforeSculpt = VisionState.VANILLA;

    /** Live pose being edited in {@link VisionState#SCULPTING}. */
    private final CameraPose sculptPose = new CameraPose();

    /** Pose the current blend started from. */
    private final CameraPose blendFrom = new CameraPose();

    /** Last pose actually handed to the camera, so re-entering edit mode is seamless. */
    private final CameraPose lastApplied = new CameraPose();

    private boolean hasLastApplied;

    /** Whether the camera currently renders away from the head (drives the hand model). */
    private boolean renderedDetached;

    private long blendStartNs;
    private long blendDurationNs;
    private long lastEditClickMs;

    private String hintKey;
    private Object[] hintArgs = new Object[0];
    private long hintUntilMs;

    private VisionController() {
    }

    // ------------------------------------------------------------------ state

    public VisionState state() {
        return this.state;
    }

    public boolean isOverriding() {
        return this.state.isOverriding();
    }

    public boolean isSculpting() {
        return this.state.isSculpting();
    }

    public boolean consumesPlayerInput() {
        return this.state.isSculpting();
    }

    public CameraPose sculptPose() {
        return this.sculptPose;
    }

    public CameraPose blendFrom() {
        return this.blendFrom;
    }

    public boolean isRenderedDetached() {
        return this.renderedDetached;
    }

    public void setRenderedDetached(boolean detached) {
        this.renderedDetached = detached;
    }

    /** The pose to render, or {@code null} when vanilla should own the camera. */
    public CameraPose targetPose() {
        VisionConfig config = VisionConfig.get();

        return switch (this.state) {
            case SCULPTING -> this.sculptPose;
            case ACTIVE -> config.pose();
            default -> null;
        };
    }

    /** 0..1 blend progress, driven by wall clock so it is independent of frame spikes. */
    public float blendProgress() {
        if (this.blendDurationNs <= 0L) {
            return 1.0F;
        }

        long elapsed = System.nanoTime() - this.blendStartNs;

        if (elapsed <= 0L) {
            return 0.0F;
        }

        return Math.min(1.0F, (float) elapsed / (float) this.blendDurationNs);
    }

    public void markLeft() {
        if (this.state == VisionState.LEAVING) {
            this.state = VisionState.VANILLA;
            FreeVision.LOG.info("FreeVision state -> {}", this.state);
        }
    }

    /** Called by the bridge with the pose it is about to render. */
    public void recordApplied(CameraPose pose) {
        this.lastApplied.set(pose);
        this.hasLastApplied = true;
    }

    // ------------------------------------------------------------------- tick

    /** Runs at the head of {@code Minecraft.tick}, before the player's own tick. */
    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            reset();

            return;
        }

        // A screen owns the keyboard and the mouse. Never change state behind it, and never steal a
        // key press the player meant for a GUI.
        if (minecraft.screen != null) {
            tickHint();

            return;
        }

        VisionConfig config = VisionConfig.get();
        handleKeys(minecraft, config);

        if (this.state.isSculpting()) {
            handleMovement(minecraft, config.settings);
            handleLook(minecraft, config.settings);
            handleRoll(minecraft, config.settings);
            handleDolly(minecraft, config.settings);
            handleReset();
        }

        tickHint();
    }

    private void handleKeys(Minecraft minecraft, VisionConfig config) {

        if (consume(VisionKeybinds.edit)) {
            long now = System.currentTimeMillis();

            if (now - this.lastEditClickMs >= EDIT_DEBOUNCE_MS) {
                this.lastEditClickMs = now;

                if (this.state.isSculpting()) {
                    finishSculpt(config);
                } else {
                    beginSculpt(minecraft, config);
                }
            }
        }

        if (consume(VisionKeybinds.vision)) {
            switch (this.state) {
                case VANILLA -> activate(minecraft, config);
                case ACTIVE -> deactivate(config);
                case SCULPTING -> finishSculpt(config);
                // Leaving is only ~220ms long; treat the press as "turn it back on".
                case LEAVING -> activate(minecraft, config);
            }
        }

        if (consume(VisionKeybinds.lock)) {
            toggleLock(minecraft, config);
        }


        if (consume(VisionKeybinds.discard)) {
            cancel();
        }
    }

    /** One press = one action, no matter how many clicks queued up this tick. */
    private static boolean consume(KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }

        boolean clicked = mapping.consumeClick();

        while (mapping.consumeClick()) {
            // drop repeats / double taps within the same tick
        }

        return clicked;
    }

    /**
     * Reads the pose the camera is showing right now. Used to start an edit session or a blend from
     * exactly what the player sees, including plain vanilla first/third person and bed cameras.
     */
    private CameraPose captureCamera(Minecraft minecraft) {
        if (minecraft.player == null) {
            return new CameraPose();
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();

        if (camera == null || !camera.isInitialized()) {
            return new CameraPose();
        }

        float partialTick = 1.0F;

        return CameraPose.fromWorld(
                minecraft.player.getEyePosition(partialTick),
                camera.getPosition(),
                minecraft.player.getViewYRot(partialTick),
                minecraft.player.getViewXRot(partialTick),
                camera.getYRot(),
                camera.getXRot(),
                false);
    }

    private void beginSculpt(Minecraft minecraft, VisionConfig config) {
        this.blendDurationNs = 0L;

        if (this.state == VisionState.ACTIVE) {
            this.stateBeforeSculpt = VisionState.ACTIVE;
            CameraPose active = config.pose();
            this.sculptPose.set(active != null ? active : captureCamera(minecraft));
        } else if (this.state == VisionState.LEAVING && this.hasLastApplied) {
            // Re-entered mid-exit: keep the exact frame the player is looking at.
            this.stateBeforeSculpt = VisionState.VANILLA;
            this.sculptPose.set(this.lastApplied);
        } else {
            this.stateBeforeSculpt = VisionState.VANILLA;
            this.sculptPose.set(captureCamera(minecraft));
        }

        this.sculptPose.clamp(config.settings.maxOffset);
        this.blendFrom.set(this.sculptPose);
        this.state = VisionState.SCULPTING;
        FreeVision.LOG.info("FreeVision state -> {}", this.state);
        showHint("freevision.hint.editing", HINT_SHORT_MS, editKeyLabel(), discardKeyLabel());
    }

    private void finishSculpt(VisionConfig config) {
        this.sculptPose.clamp(config.settings.maxOffset);
        config.storePose(this.sculptPose);

        if (!config.save()) {
            // Stay in edit mode so nothing is lost and the player can retry.
            showHint("freevision.hint.save_failed", HINT_LONG_MS);

            return;
        }

        this.state = VisionState.ACTIVE;
        this.stateBeforeSculpt = VisionState.ACTIVE;
        this.blendFrom.set(this.sculptPose);
        this.blendDurationNs = 0L;
        FreeVision.LOG.info("FreeVision state -> {} (saved)", this.state);

        click(config, 1.5F);
        showHint("freevision.hint.saved", HINT_SHORT_MS, visionKeyLabel());
    }

    private void activate(Minecraft minecraft, VisionConfig config) {
        if (!config.hasPose()) {
            showHint("freevision.hint.no_preset", HINT_LONG_MS, editKeyLabel());

            return;
        }

        this.blendFrom.set(captureCamera(minecraft));
        this.state = VisionState.ACTIVE;
        this.stateBeforeSculpt = VisionState.ACTIVE;
        FreeVision.LOG.info("FreeVision state -> {}", this.state);
        startTransition(config);
        click(config, 1.2F);
        showHint("freevision.hint.active", HINT_SHORT_MS, visionKeyLabel());
    }

    private void deactivate(VisionConfig config) {
        CameraPose origin = this.hasLastApplied ? this.lastApplied : config.pose();
        this.blendFrom.set(origin != null ? origin : new CameraPose());
        this.state = VisionState.LEAVING;
        FreeVision.LOG.info("FreeVision state -> {}", this.state);
        startTransition(config);
        click(config, 0.9F);
        showHint("freevision.hint.inactive", HINT_SHORT_MS, visionKeyLabel());
    }

    /** Discard key: throw the edits away and go back to where the player was. */
    public boolean cancel() {
        if (!this.state.isSculpting()) {
            return false;
        }

        VisionConfig config = VisionConfig.get();
        this.blendFrom.set(this.sculptPose);

        if (this.stateBeforeSculpt == VisionState.ACTIVE && config.hasPose()) {
            this.state = VisionState.ACTIVE;
        } else {
            this.state = VisionState.LEAVING;
        }

        startTransition(config);
        showHint("freevision.hint.cancelled", HINT_SHORT_MS);

        return true;
    }

    /**
     * Flips between "the camera tracks the player's look" and "the camera holds a world direction".
     * The conversion happens at the current frame, so the picture does not jump.
     */
    private void toggleLock(Minecraft minecraft, VisionConfig config) {
        if (minecraft.player == null) {
            return;
        }

        float viewYaw = minecraft.player.getViewYRot(1.0F);
        float viewPitch = minecraft.player.getViewXRot(1.0F);

        if (this.state.isSculpting()) {
            this.sculptPose.set(this.sculptPose.convertMode(!this.sculptPose.absolute, viewYaw, viewPitch));
            this.sculptPose.clamp(config.settings.maxOffset);
            showHint(this.sculptPose.absolute ? "freevision.hint.lock_on" : "freevision.hint.lock_off", HINT_SHORT_MS);

            return;
        }

        if (this.state == VisionState.ACTIVE) {
            CameraPose active = config.pose();

            if (active == null) {
                return;
            }

            CameraPose converted = active.convertMode(!active.absolute, viewYaw, viewPitch);
            config.storePose(converted);

            if (!config.save()) {
                showHint("freevision.hint.save_failed", HINT_LONG_MS);

                return;
            }

            this.blendFrom.set(active);
            startTransition(config);
            showHint(converted.absolute ? "freevision.hint.lock_on" : "freevision.hint.lock_off", HINT_SHORT_MS);

            return;
        }

        showHint("freevision.hint.lock_needs_view", HINT_LONG_MS, editKeyLabel());
    }

    private void startTransition(VisionConfig config) {
        if (!config.settings.smoothTransitions || config.settings.transitionMs <= 0) {
            this.blendDurationNs = 0L;
            this.blendStartNs = System.nanoTime();

            return;
        }

        this.blendStartNs = System.nanoTime();
        this.blendDurationNs = config.settings.transitionMs * 1_000_000L;
    }

    // ------------------------------------------------------------------ input

    /**
     * Mouse look while adjusting. The values handed in are the exact deltas vanilla would have
     * applied to the player's rotation, so sensitivity, invert-Y and smooth-camera all keep working
     * as the player expects.
     *
     * @return {@code true} when the turn was consumed by the camera
     */
    public boolean handleMouseTurn(double yawDelta, double pitchDelta) {
        if (!this.state.isSculpting()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.screen != null) {
            return false;
        }

        applyTurn(minecraft, (float) (yawDelta * VANILLA_TURN_SCALE), (float) (pitchDelta * VANILLA_TURN_SCALE));

        return true;
    }

    /**
     * Mouse wheel while adjusting: dolly the rig along its own view axis.
     *
     * @return {@code true} when the scroll was consumed
     */
    public boolean handleScroll(double scrollDelta) {
        if (!this.state.isSculpting() || scrollDelta == 0.0D) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.screen != null || minecraft.mouseHandler == null || !minecraft.mouseHandler.isMouseGrabbed()) {
            return false;
        }

        this.sculptPose.distance -= scrollDelta * 0.5D;
        this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);

        return true;
    }

    private void applyTurn(Minecraft minecraft, float yaw, float pitch) {
        this.sculptPose.yawOffset = Mth.wrapDegrees(this.sculptPose.yawOffset + yaw);

        if (this.sculptPose.absolute) {
            this.sculptPose.pitchOffset = Mth.clamp(this.sculptPose.pitchOffset + pitch,
                    -CameraPose.MAX_PITCH, CameraPose.MAX_PITCH);
        } else {
            float viewPitch = minecraft.player == null ? 0.0F : minecraft.player.getXRot();
            this.sculptPose.pitchOffset = Mth.clamp(this.sculptPose.pitchOffset + pitch,
                    -CameraPose.MAX_PITCH - viewPitch,
                    CameraPose.MAX_PITCH - viewPitch);
        }

        this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);
    }

    /** Keyboard-only camera turning, so a mouse (or a steady hand) is not required to use the mod. */
    private void handleLook(Minecraft minecraft, VisionSettings settings) {
        float step = LOOK_STEP;

        if (settings.precisionModifier && physicallyDown(minecraft, minecraft.options.keySprint)) {
            step *= 0.25F;
        }

        float yaw = 0.0F;
        float pitch = 0.0F;

        if (physicallyDown(minecraft, VisionKeybinds.lookLeft)) {
            yaw -= step;
        }

        if (physicallyDown(minecraft, VisionKeybinds.lookRight)) {
            yaw += step;
        }

        if (physicallyDown(minecraft, VisionKeybinds.lookUp)) {
            pitch -= step;
        }

        if (physicallyDown(minecraft, VisionKeybinds.lookDown)) {
            pitch += step;
        }

        if (yaw == 0.0F && pitch == 0.0F) {
            return;
        }

        applyTurn(minecraft, yaw, pitch);
    }

    private void handleDolly(Minecraft minecraft, VisionSettings settings) {
        double step = Math.max(0.05D, settings.sculptSpeed);
        boolean moved = false;

        while (VisionKeybinds.dollyIn != null && VisionKeybinds.dollyIn.consumeClick()) {
            this.sculptPose.distance -= step;
            moved = true;
        }

        while (VisionKeybinds.dollyOut != null && VisionKeybinds.dollyOut.consumeClick()) {
            this.sculptPose.distance += step;
            moved = true;
        }

        if (moved) {
            this.sculptPose.clamp(settings.maxOffset);
        }
    }

    private void handleReset() {
        if (VisionKeybinds.reset == null) {
            return;
        }

        while (VisionKeybinds.reset.consumeClick()) {
            this.sculptPose.reset();
            showHint("freevision.hint.reset", HINT_SHORT_MS);
        }
    }

    private void handleMovement(Minecraft minecraft, VisionSettings settings) {
        double step = settings.sculptSpeed;

        if (step <= 0.0D) {
            return;
        }

        if (settings.precisionModifier && physicallyDown(minecraft, minecraft.options.keySprint)) {
            step *= 0.2D;
        }

        double forward = 0.0D;
        double strafe = 0.0D;
        double vertical = 0.0D;

        if (physicallyDown(minecraft, minecraft.options.keyUp)) {
            forward += 1.0D;
        }

        if (physicallyDown(minecraft, minecraft.options.keyDown)) {
            forward -= 1.0D;
        }

        if (physicallyDown(minecraft, minecraft.options.keyLeft)) {
            strafe += 1.0D;
        }

        if (physicallyDown(minecraft, minecraft.options.keyRight)) {
            strafe -= 1.0D;
        }

        if (physicallyDown(minecraft, minecraft.options.keyJump)) {
            vertical += 1.0D;
        }

        if (physicallyDown(minecraft, minecraft.options.keyShift)) {
            vertical -= 1.0D;
        }

        if (forward == 0.0D && strafe == 0.0D && vertical == 0.0D) {
            return;
        }

        // Moving the rig "forward" means shortening its backward offset, because distance is
        // measured along the camera's own view axis.
        this.sculptPose.distance -= forward * step;
        this.sculptPose.side += strafe * step;
        this.sculptPose.height += vertical * step;
        this.sculptPose.clamp(settings.maxOffset);
    }

    private void handleRoll(Minecraft minecraft, VisionSettings settings) {
        double rollStep = ROLL_STEP;

        if (settings.precisionModifier && physicallyDown(minecraft, minecraft.options.keySprint)) {
            rollStep *= 0.25D;
        }

        boolean left = physicallyDown(minecraft, VisionKeybinds.rollLeft);
        boolean right = physicallyDown(minecraft, VisionKeybinds.rollRight);

        if (!left && !right) {
            return;
        }

        this.sculptPose.roll += (float) ((left ? -rollStep : 0.0D) + (right ? rollStep : 0.0D));
        this.sculptPose.clamp(settings.maxOffset);
    }

    /**
     * Reads the physical key state instead of {@link KeyMapping#isDown()}.
     *
     * <p>Shift and sprint are {@code ToggleKeyMapping} instances when the player enables
     * "toggle crouch/sprint": their {@code isDown()} stays true after a single tap, which would make
     * the rig sink forever and the precision modifier stick. Polling the window keeps the behaviour
     * identical to what the finger is doing.
     */
    private static boolean physicallyDown(Minecraft minecraft, KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }

        InputConstants.Key key = InputConstants.getKey(mapping.saveString());

        if (key.getType() == InputConstants.Type.KEYSYM && minecraft.getWindow() != null) {
            return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key.getValue());
        }

        return mapping.isDown();
    }

    // ------------------------------------------------------------------ misc

    public void reset() {
        this.state = VisionState.VANILLA;
        this.stateBeforeSculpt = VisionState.VANILLA;
        this.hasLastApplied = false;
        this.renderedDetached = false;
        this.blendDurationNs = 0L;
        this.hintKey = null;
        this.hintArgs = new Object[0];
        this.sculptPose.reset();
        this.blendFrom.reset();
        this.lastApplied.reset();
    }

    public void showHint(String translationKey, long millis, Object... args) {
        this.hintKey = translationKey;
        this.hintArgs = args.clone();
        this.hintUntilMs = System.currentTimeMillis() + (millis <= 0L ? HINT_SHORT_MS : millis);
    }

    public String hintKey() {
        return this.hintKey;
    }

    public Object[] hintArgs() {
        return this.hintArgs;
    }

    private void tickHint() {
        if (this.hintKey != null && this.hintUntilMs < System.currentTimeMillis()) {
            this.hintKey = null;
            this.hintArgs = new Object[0];
        }
    }

    private String editKeyLabel() {
        return label(VisionKeybinds.edit);
    }

    private String discardKeyLabel() {
        return label(VisionKeybinds.discard);
    }

    private String visionKeyLabel() {
        return label(VisionKeybinds.vision);
    }

    /** The translated name of whatever key the player has bound to {@code mapping}. */
    public static String label(KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
    }

    private void click(VisionConfig config, float pitch) {
        if (!config.settings.playSounds) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
        }
    }
}
