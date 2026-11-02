package cn.xm1221.freevision.mixin;

import cn.xm1221.freevision.camera.CameraPose;
import cn.xm1221.freevision.camera.VisionCameraBridge;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The only place the mod edits the camera.
 *
 * <p>Vanilla {@code Camera.setup} runs once per frame, computes the eye-relative camera and - in
 * third person - dollies it back. Injecting at its tail means our pose is written <i>last</i>, so
 * it wins over F5, sleeping-in-bed, spectator and any other camera source while never changing
 * their logic.
 *
 * <p>Two details worth knowing:
 * <ul>
 *   <li>{@code Camera#setRotation} rebuilds the look/up/left basis from yaw and pitch. Roll is not
 *       part of that call, so it is applied afterwards by rotating the quaternion around the local
 *       Z axis (the view axis) and re-deriving the basis.</li>
 *   <li>{@code detached = true} is what makes {@code LevelRenderer} draw the local player even
 *       though the camera entity is still the player. Without it the player would be invisible
 *       from their own custom perspective.</li>
 * </ul>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    @Final
    private Quaternionf rotation;

    @Shadow
    @Final
    private Vector3f forwards;

    @Shadow
    @Final
    private Vector3f up;

    @Shadow
    @Final
    private Vector3f left;

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void freevision$applyCustomPerspective(BlockGetter level, Entity entity, boolean thirdPerson,
            boolean reversed, float partialTick, CallbackInfo callbackInfo) {
        CameraEyeHeightAccessor eyeHeight = (CameraEyeHeightAccessor) (Object) this;

        // Same interpolated eye anchor vanilla uses, so crouching/swimming stays soft.
        Vec3 eye = new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY())
                        + Mth.lerp(partialTick, eyeHeight.freevision$eyeHeightOld(), eyeHeight.freevision$eyeHeight()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));

        CameraPose pose = VisionCameraBridge.computePose(entity, (Camera) (Object) this, partialTick, eye);

        if (pose == null) {
            return;
        }

        this.setRotation(pose.cameraYaw(entity, partialTick), pose.cameraPitch(entity, partialTick));

        if (pose.roll != 0.0F) {
            this.freevision$roll(pose.roll);
        }

        this.setPosition(VisionCameraBridge.computePosition(entity, pose, partialTick, eye));

        // Let LevelRenderer know the camera left the head, so the player model is drawn.
        this.detached = true;
    }

    @Unique
    private void freevision$roll(float degrees) {
        this.rotation.rotateZ((float) Math.toRadians(degrees));
        this.forwards.set(0.0F, 0.0F, 1.0F).rotate(this.rotation);
        this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
        this.left.set(1.0F, 0.0F, 0.0F).rotate(this.rotation);
    }
}
