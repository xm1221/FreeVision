package cn.xm1221.freevision.camera;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A custom perspective, expressed entirely <b>relative to the player</b>.
 *
 * <p>This is the whole reason the feature is not a free camera: nothing here is a world
 * coordinate. The pose is a small rig that hangs off the player's eye every single frame,
 * so once saved it keeps framing the player while they walk, fall, ride, swim or fight.
 *
 * <p>Two coordinate pieces make that work:
 *
 * <ul>
 *   <li><b>{@link #yawOffset} / {@link #pitchOffset} / {@link #roll}</b> — added on top of the
 *       player's interpolated view rotation, so the crosshair keeps pointing at what the player
 *       aims at (pitch/yaw are tracking, not fixed).</li>
 *   <li><b>{@link #distance} / {@link #height} / {@link #side}</b> — the eye-relative offset,
 *       measured in a frame rotated by the <i>camera's own</i> yaw. Because of that the rig orbits
 *       the player when the view turns: exactly what a third-person camera does, never a drifting
 *       floating point in the world.</li>
 * </ul>
 *
 * <p>Screen-space meaning of the three rig axes, for a camera that looks north while the
 * player faces north:
 * {@code distance} pushes the camera backwards along its own view axis,
 * {@code side} slides it along its left vector (positive = camera moves left, player appears
 * right of screen centre), {@code height} lifts it straight up in world space.
 *
 * <p>Instances are deliberately mutable: the sculpt loop edits one in place and Gson fills the
 * fields straight from {@code config/freevision.json}.
 */
public final class CameraPose {
    /** Upper bound for pitch, tiny epsilon below the vanilla clamp so look vectors stay sane. */
    public static final float MAX_PITCH = 89.9F;

    public float yawOffset;
    public float pitchOffset;
    public float roll;
    public double distance;
    public double height;
    public double side;

    public CameraPose() {
    }

    public CameraPose(float yawOffset, float pitchOffset, float roll, double distance, double height, double side) {
        this.yawOffset = yawOffset;
        this.pitchOffset = pitchOffset;
        this.roll = roll;
        this.distance = distance;
        this.height = height;
        this.side = side;
    }

    public CameraPose copy() {
        return new CameraPose(this.yawOffset, this.pitchOffset, this.roll, this.distance, this.height, this.side);
    }

    /** In-place copy of another pose. */
    public void set(CameraPose other) {
        this.yawOffset = other.yawOffset;
        this.pitchOffset = other.pitchOffset;
        this.roll = other.roll;
        this.distance = other.distance;
        this.height = other.height;
        this.side = other.side;
    }

    public void reset() {
        this.yawOffset = 0.0F;
        this.pitchOffset = 0.0F;
        this.roll = 0.0F;
        this.distance = 0.0D;
        this.height = 0.0D;
        this.side = 0.0D;
    }

    /** True when the pose is visually indistinguishable from the player's own eyes. */
    public boolean isNeutral() {
        return Math.abs(this.yawOffset) < 1.0E-4F
                && Math.abs(this.pitchOffset) < 1.0E-4F
                && Math.abs(this.roll) < 1.0E-4F
                && Math.abs(this.distance) < 1.0E-4D
                && Math.abs(this.height) < 1.0E-4D
                && Math.abs(this.side) < 1.0E-4D;
    }

    /**
     * Keeps the rig inside a sane envelope: no camera kilometres behind the player, no upside
     * down flips. Called after every sculpt edit and before a save.
     */
    public void clamp(double maxOffset) {
        this.distance = Mth.clamp(this.distance, -maxOffset, maxOffset);
        this.side = Mth.clamp(this.side, -maxOffset, maxOffset);
        this.height = Mth.clamp(this.height, -maxOffset, maxOffset);
        this.pitchOffset = Mth.clamp(this.pitchOffset, -360.0F, 360.0F);
        this.roll = Mth.clamp(this.roll, -90.0F, 90.0F);
        this.yawOffset = Mth.wrapDegrees(this.yawOffset);
    }

    /**
     * Re-projects a <i>world</i> camera pose back into rig space relative to a player.
     *
     * <p>Used for two things: seeding the sculpt session from whatever vanilla camera the player
     * currently has (first person, F5 third person, bed, ...), and converting the live vanilla
     * camera into a blend target while leaving the custom view.
     *
     * @param eye       player's interpolated eye position
     * @param cameraPos the camera position we want to express as a rig
     * @param viewYaw   player's interpolated view yaw (degrees)
     * @param viewPitch player's interpolated view pitch (degrees)
     * @param cameraYaw the world yaw of that camera (degrees)
     * @param cameraPitch the world pitch of that camera (degrees)
     */
    public static CameraPose fromWorld(Vec3 eye, Vec3 cameraPos, float viewYaw, float viewPitch, float cameraYaw, float cameraPitch) {
        CameraPose pose = new CameraPose();
        double radians = Math.toRadians(cameraYaw);
        double backX = Math.sin(radians);
        double backZ = -Math.cos(radians);
        double leftX = Math.cos(radians);
        double leftZ = Math.sin(radians);

        double dx = cameraPos.x - eye.x;
        double dy = cameraPos.y - eye.y;
        double dz = cameraPos.z - eye.z;

        pose.distance = dx * backX + dz * backZ;
        pose.side = dx * leftX + dz * leftZ;
        pose.height = dy;
        pose.yawOffset = Mth.wrapDegrees(cameraYaw - viewYaw);
        pose.pitchOffset = cameraPitch - viewPitch;
        return pose;
    }

    /** Camera world yaw for this rig, in degrees. */
    public float cameraYaw(Entity player, float partialTick) {
        return player.getViewYRot(partialTick) + this.yawOffset;
    }

    /** Camera world pitch for this rig, clamped like vanilla look control. */
    public float cameraPitch(Entity player, float partialTick) {
        return Mth.clamp(player.getViewXRot(partialTick) + this.pitchOffset, -MAX_PITCH, MAX_PITCH);
    }

    /** Camera world position for this rig. */
    public Vec3 position(Entity player, float partialTick) {
        return this.position(player.getEyePosition(partialTick), this.cameraYaw(player, partialTick));
    }

    /** Camera world position for this rig, given an explicit (already smoothed) eye position. */
    public Vec3 position(Vec3 eye, float cameraYaw) {
        double radians = Math.toRadians(cameraYaw);
        double backX = Math.sin(radians);
        double backZ = -Math.cos(radians);
        double leftX = Math.cos(radians);
        double leftZ = Math.sin(radians);

        double x = eye.x + backX * this.distance + leftX * this.side;
        double y = eye.y + this.height;
        double z = eye.z + backZ * this.distance + leftZ * this.side;
        return new Vec3(x, y, z);
    }

    /**
     * Interpolates two rigs. Yaw uses the shortest arc so a blend from 350&deg; to 10&deg; travels
     * 20&deg; instead of wrapping 340&deg; across the screen.
     */
    public static CameraPose lerp(CameraPose from, CameraPose to, float delta) {
        CameraPose out = new CameraPose();
        out.yawOffset = lerpAngle(from.yawOffset, to.yawOffset, delta);
        out.pitchOffset = Mth.lerp(delta, from.pitchOffset, to.pitchOffset);
        out.roll = Mth.lerp(delta, from.roll, to.roll);
        out.distance = Mth.lerp(delta, from.distance, to.distance);
        out.height = Mth.lerp(delta, from.height, to.height);
        out.side = Mth.lerp(delta, from.side, to.side);
        return out;
    }

    /** Shortest-arc angle interpolation, in degrees. */
    public static float lerpAngle(float from, float to, float delta) {
        float difference = Mth.wrapDegrees(to - from);
        return from + difference * delta;
    }

    @Override
    public String toString() {
        return String.format("CameraPose[yaw=%.1f pitch=%.1f roll=%.1f dist=%.2f h=%.2f side=%.2f]",
                this.yawOffset, this.pitchOffset, this.roll, this.distance, this.height, this.side);
    }
}
