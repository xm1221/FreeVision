package cn.xm1221.freevision.camera;

import cn.xm1221.freevision.client.VisionKeybinds;
import cn.xm1221.freevision.config.VisionConfig;
import cn.xm1221.freevision.config.VisionSettings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The single owner of the custom-perspective state machine.
 *
 * <p>Everything else in the mod is a thin adapter around this class: mixins forward raw input
 * here, the camera mixin asks it for the pose to render, the HUD asks it what to draw.
 *
 * <p>Threading: only ever touched from the client thread (input mixins and the render loop).
 */
public final class VisionController {
    private static final VisionController INSTANCE = new VisionController();

    /** Grace window for the "did the key press start this tick" bookkeeping. */
    private static final float VANILLA_TURN_SCALE = 0.15F;

    public static VisionController get() {
        return INSTANCE;
    }

    private VisionState state = VisionState.VANILLA;
    private VisionState stateBeforeSculpt = VisionState.VANILLA;

    /** Live pose being edited in {@link VisionState#SCULPTING} / {@link VisionState#DRAFT}. */
    private final CameraPose sculptPose = new CameraPose();

    /** Pose the current blend started from. */
    private final CameraPose blendFrom = new CameraPose();

    private long blendStartNs;
    private long blendDurationNs;

    private boolean keyWasDown;
    private long keyPressStartMs;
    private boolean holdHandled;

    /** Set when the blend origin still has to be captured from the vanilla camera. */
    private boolean pendingVanillaSeed;

    /** Whether that capture should also become the starting sculpt pose. */
    private boolean seedSculptFromVanilla;

    private String hintKey;
    private long hintUntilMs;
    private int hintSlot;

    private VisionController() {
    }

    // ------------------------------------------------------------------ state

    public VisionState state() {
        return this.state;
    }

    public boolean isOverriding() {
        return this.state.isOverriding();
    }

    public boolean isApplied() {
        return this.state.isApplied();
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

    /** The pose to render, or {@code null} when vanilla should own the camera. */
    public CameraPose targetPose() {
        VisionConfig config = VisionConfig.get();

        return switch (this.state) {
            case SCULPTING, DRAFT -> this.sculptPose;
            case ACTIVE -> config.activePreset();
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
        }
    }

    public boolean pendingVanillaSeed() {
        return this.pendingVanillaSeed;
    }

    /**
     * Called by the camera bridge with the vanilla pose it just computed, when a transition needs
     * to start exactly where the current (vanilla) camera is.
     *
     * @return {@code true} when the sculpt pose was seeded as well
     */
    public boolean seedFromVanilla(Vec3 eye, Vec3 cameraPos, float viewYaw, float viewPitch, float cameraYaw, float cameraPitch) {
        if (!this.pendingVanillaSeed) {
            return false;
        }

        CameraPose vanilla = CameraPose.fromWorld(eye, cameraPos, viewYaw, viewPitch, cameraYaw, cameraPitch);
        this.blendFrom.set(vanilla);
        this.pendingVanillaSeed = false;

        if (this.seedSculptFromVanilla) {
            this.sculptPose.set(vanilla);
            this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);
            this.seedSculptFromVanilla = false;

            return true;
        }

        this.blendStartNs = System.nanoTime();

        return false;
    }

    // ------------------------------------------------------------------- tick

    /** Runs at the head of {@code Minecraft.tick}, before the player's own tick. */
    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            reset();

            return;
        }

        VisionConfig config = VisionConfig.get();
        VisionSettings settings = config.settings;

        // A screen (inventory, chat, pause) steals all input; park the session in DRAFT so nothing
        // the player sculpted is lost.
        if (minecraft.screen != null && this.state.isSculpting()) {
            enterDraft();
        }

        handleSlotKeys(config);

        if (this.state == VisionState.VANILLA && this.hintUntilMs < System.currentTimeMillis()) {
            this.hintKey = null;
        }

        handleMainKey(minecraft, config, settings);

        if (this.state.isSculpting() && minecraft.screen == null) {
            handleSculptMovement(minecraft, settings);
            handleRoll(minecraft, settings);

            while (VisionKeybinds.reset != null && VisionKeybinds.reset.consumeClick()) {
                this.sculptPose.reset();
                showHint("freevision.hint.reset", 900);
            }
        }
    }

    private void handleSlotKeys(VisionConfig config) {
        if (VisionKeybinds.nextSlot != null && VisionKeybinds.nextSlot.consumeClick()) {
            int slot = config.cycleSlot(1);
            config.save();

            if (this.state == VisionState.ACTIVE) {
                this.blendFrom.set(this.sculptPose);
                startTransition(config);
            }

            this.hintSlot = slot + 1;
            showHint("freevision.hint.slot", 1200);
        }
    }

    private void handleMainKey(Minecraft minecraft, VisionConfig config, VisionSettings settings) {
        KeyMapping key = VisionKeybinds.vision;

        if (key == null) {
            return;
        }

        boolean down = key.isDown();
        long now = System.currentTimeMillis();

        if (down) {
            if (!this.keyWasDown) {
                this.keyPressStartMs = now;
                this.holdHandled = false;
            }

            if (!this.holdHandled && now - this.keyPressStartMs >= Math.max(60, settings.holdThresholdMs)) {
                this.holdHandled = true;
                beginSculpt();
            }
        } else if (this.keyWasDown) {
            if (this.state.isSculpting()) {
                enterDraft();
            } else if (!this.holdHandled) {
                onTap(config);
            }
        }

        this.keyWasDown = down;
    }

    private void onTap(VisionConfig config) {
        switch (this.state) {
            case VANILLA -> {
                if (config.hasPreset()) {
                    activate();
                } else {
                    showHint("freevision.hint.no_preset", 2200);
                }
            }
            case ACTIVE -> deactivate();
            case DRAFT -> commit(config);
            default -> {
            }
        }
    }

    private void beginSculpt() {
        if (this.state == VisionState.DRAFT) {
            this.state = VisionState.SCULPTING;
            return;
        }

        this.stateBeforeSculpt = this.state;
        this.blendDurationNs = 0L;

        if (this.state == VisionState.ACTIVE) {
            CameraPose active = VisionConfig.get().activePreset();

            if (active != null) {
                this.sculptPose.set(active);
                this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);
            } else {
                this.sculptPose.reset();
            }

            this.state = VisionState.SCULPTING;
            this.blendFrom.set(this.sculptPose);
        } else {
            // Seed both the blend origin and the starting pose from the live vanilla camera, so the
            // camera does not jump when the key is held.
            this.state = VisionState.SCULPTING;
            this.pendingVanillaSeed = true;
            this.seedSculptFromVanilla = true;
        }

        showHint("freevision.hint.sculpting", 0);
    }

    private void enterDraft() {
        if (this.state != VisionState.SCULPTING) {
            return;
        }

        this.state = VisionState.DRAFT;
        this.blendDurationNs = 0L;
        this.blendFrom.set(this.sculptPose);
        showHint("freevision.hint.draft", 0);
    }

    private void commit(VisionConfig config) {
        this.sculptPose.clamp(config.settings.maxOffset);
        config.storeActiveSlot(this.sculptPose);
        config.save();

        this.state = VisionState.ACTIVE;
        this.stateBeforeSculpt = VisionState.ACTIVE;
        this.blendFrom.set(this.sculptPose);
        this.blendDurationNs = 0L;

        click(config, 1.5F);
        showHint("freevision.hint.saved", 1400);
    }

    private void activate() {
        VisionConfig config = VisionConfig.get();

        if (!config.hasPreset()) {
            showHint("freevision.hint.no_preset", 1800);
            return;
        }

        this.state = VisionState.ACTIVE;
        this.stateBeforeSculpt = VisionState.ACTIVE;
        this.pendingVanillaSeed = true;
        this.seedSculptFromVanilla = false;
        startTransition(config);
        click(config, 1.2F);
    }

    private void deactivate() {
        VisionConfig config = VisionConfig.get();
        CameraPose active = config.activePreset();

        this.blendFrom.set(active != null ? active : this.sculptPose);
        this.state = VisionState.LEAVING;
        startTransition(config);
        click(config, 0.9F);
    }

    /** Esc: throw the sculpted pose away. */
    public boolean cancel() {
        if (this.state == VisionState.SCULPTING) {
            enterDraft();
        }

        if (this.state != VisionState.DRAFT) {
            return false;
        }

        VisionConfig config = VisionConfig.get();
        this.blendFrom.set(this.sculptPose);

        if (this.stateBeforeSculpt == VisionState.ACTIVE && config.hasPreset()) {
            this.state = VisionState.ACTIVE;
        } else {
            this.state = VisionState.LEAVING;
            this.stateBeforeSculpt = VisionState.VANILLA;
        }

        startTransition(config);
        showHint("freevision.hint.cancelled", 1200);

        return true;
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
     * Mouse look while sculpting. The values handed in are the exact deltas vanilla would have
     * applied to the player's rotation, so sensitivity, invert-Y and smooth-camera all keep
     * working as the player expects.
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

        this.sculptPose.yawOffset = Mth.wrapDegrees(this.sculptPose.yawOffset + (float) (yawDelta * VANILLA_TURN_SCALE));
        this.sculptPose.pitchOffset += (float) (pitchDelta * VANILLA_TURN_SCALE);

        float viewPitch = minecraft.player.getXRot();
        this.sculptPose.pitchOffset = Mth.clamp(this.sculptPose.pitchOffset,
                -CameraPose.MAX_PITCH - viewPitch,
                CameraPose.MAX_PITCH - viewPitch);
        this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);

        return true;
    }

    /**
     * Mouse wheel while sculpting: dolly the rig along its own view axis.
     *
     * @return {@code true} when the scroll was consumed
     */
    public boolean handleScroll(double scrollDelta) {
        if (!this.state.isSculpting() || scrollDelta == 0.0D) {
            return false;
        }

        this.sculptPose.distance -= scrollDelta * 0.5D;
        this.sculptPose.clamp(VisionConfig.get().settings.maxOffset);

        return true;
    }

    private void handleSculptMovement(Minecraft minecraft, VisionSettings settings) {
        double step = settings.sculptSpeed;

        if (step <= 0.0D) {
            return;
        }

        if (settings.precisionModifier && minecraft.options.keySprint.isDown()) {
            step *= 0.2D;
        }

        double forward = 0.0D;
        double strafe = 0.0D;
        double vertical = 0.0D;

        if (minecraft.options.keyUp.isDown()) {
            forward += 1.0D;
        }

        if (minecraft.options.keyDown.isDown()) {
            forward -= 1.0D;
        }

        if (minecraft.options.keyLeft.isDown()) {
            strafe += 1.0D;
        }

        if (minecraft.options.keyRight.isDown()) {
            strafe -= 1.0D;
        }

        if (minecraft.options.keyJump.isDown()) {
            vertical += 1.0D;
        }

        if (minecraft.options.keyShift.isDown()) {
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
        double rollStep = 1.25D;

        if (settings.precisionModifier && minecraft.options.keySprint.isDown()) {
            rollStep *= 0.25D;
        }

        boolean left = VisionKeybinds.rollLeft != null && VisionKeybinds.rollLeft.isDown();
        boolean right = VisionKeybinds.rollRight != null && VisionKeybinds.rollRight.isDown();

        if (!left && !right) {
            return;
        }

        this.sculptPose.roll += (float) ((left ? -rollStep : 0.0D) + (right ? rollStep : 0.0D));
        this.sculptPose.clamp(settings.maxOffset);
    }

    // ------------------------------------------------------------------ misc

    public void reset() {
        this.state = VisionState.VANILLA;
        this.stateBeforeSculpt = VisionState.VANILLA;
        this.pendingVanillaSeed = false;
        this.seedSculptFromVanilla = false;
        this.keyWasDown = false;
        this.holdHandled = false;
        this.blendDurationNs = 0L;
        this.hintKey = null;
    }

    public void showHint(String translationKey, long millis) {
        this.hintKey = translationKey;
        this.hintUntilMs = millis <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() + millis;
    }

    public String hintKey() {
        if (this.hintKey == null) {
            return null;
        }

        if (this.hintUntilMs < System.currentTimeMillis()) {
            this.hintKey = null;

            return null;
        }

        return this.hintKey;
    }

    public int hintSlot() {
        return this.hintSlot;
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
