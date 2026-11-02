package cn.xm1221.freevision.camera;

import cn.xm1221.freevision.config.VisionConfig;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Glue between the camera mixin and {@link VisionController}.
 *
 * <p>It answers one question per frame: <i>given the camera vanilla just computed, what pose
 * should be rendered?</i> It also holds the two pieces of camera-side behaviour that are not pure
 * state: transition easing and clipping the rig out of solid blocks.
 */
public final class VisionCameraBridge {
    private VisionCameraBridge() {
    }

    /**
     * @param entity      the camera entity
     * @param camera      the camera, already set up by vanilla for this frame
     * @param eye         interpolated eye position (using the camera's smoothed eye height)
     * @param partialTick render interpolation factor
     * @return the rig pose to apply, or {@code null} to leave vanilla's camera untouched
     */
    public static CameraPose computePose(Entity entity, Camera camera, float partialTick, Vec3 eye) {
        VisionController controller = VisionController.get();

        if (!controller.isOverriding()) {
            return null;
        }

        float viewYaw = entity.getViewYRot(partialTick);
        float viewPitch = entity.getViewXRot(partialTick);
        Vec3 vanillaPosition = camera.getPosition();
        float vanillaYaw = camera.getYRot();
        float vanillaPitch = camera.getXRot();

        // A transition that starts "from wherever the player currently is" can only be seeded once
        // the vanilla camera for this frame exists - that moment is right here.
        controller.seedFromVanilla(eye, vanillaPosition, viewYaw, viewPitch, vanillaYaw, vanillaPitch);

        VisionState state = controller.state();
        float progress = controller.blendProgress();
        CameraPose target = state == VisionState.LEAVING
                ? CameraPose.fromWorld(eye, vanillaPosition, viewYaw, viewPitch, vanillaYaw, vanillaPitch)
                : controller.targetPose();

        if (target == null) {
            if (state == VisionState.LEAVING) {
                controller.markLeft();
            }

            return null;
        }

        if (progress >= 1.0F) {
            if (state == VisionState.LEAVING) {
                controller.markLeft();

                return null;
            }

            return target;
        }

        return CameraPose.lerp(controller.blendFrom(), target, ease(progress));
    }

    /** Final world position for a pose, optionally pulled out of geometry. */
    public static Vec3 computePosition(Entity entity, CameraPose pose, float partialTick, Vec3 eye) {
        Vec3 desired = pose.position(eye, pose.cameraYaw(entity, partialTick));
        VisionState state = VisionController.get().state();
        boolean applied = state == VisionState.ACTIVE || state == VisionState.LEAVING;

        if (!applied || !VisionConfig.get().settings.avoidClipping) {
            return desired;
        }

        return clip(entity, eye, desired);
    }

    /**
     * Shortens the eye-to-camera segment when it would end inside a block - the same courtesy
     * vanilla third person does, so the saved perspective never ends up buried in stone.
     */
    public static Vec3 clip(Entity entity, Vec3 eye, Vec3 desired) {
        if (entity.level() == null) {
            return desired;
        }

        HitResult hit = entity.level().clip(new ClipContext(eye, desired, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));

        if (hit.getType() == HitResult.Type.MISS) {
            return desired;
        }

        Vec3 direction = desired.subtract(eye);
        double length = direction.length();

        if (length < 1.0E-4D) {
            return desired;
        }

        Vec3 blocked = hit.getLocation().subtract(direction.normalize().scale(0.15D));

        return blocked.distanceToSqr(eye) < 0.02D ? eye : blocked;
    }

    /** Smoothstep easing; the derivative is zero at both ends so the fly-in never snaps. */
    public static float ease(float delta) {
        float clamped = Mth.clamp(delta, 0.0F, 1.0F);

        return clamped * clamped * (3.0F - 2.0F * clamped);
    }
}
