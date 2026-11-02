package cn.xm1221.freevision.camera;

import cn.xm1221.freevision.config.VisionConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crosshair re-targeting for the custom perspective.
 *
 * <p>Vanilla always ray-casts from the <i>entity's</i> eyes, which is why the block outline drifts
 * off-centre when you look through a shoulder camera. When FreeVision moves the camera away from
 * the player, this re-casts the pick from the camera instead so the crosshair sits on what you are
 * actually looking at.
 *
 * <p><b>Server safety:</b> the resulting hit is only accepted when it is still inside the player's
 * own reach. Any hit further than vanilla could reach from the player's eyes falls back to the
 * vanilla result, so no interaction is ever attempted at a distance the server would reject.
 */
public final class VisionPicker {
    private VisionPicker() {
    }

    public static void repick(Minecraft minecraft, float partialTick) {
        VisionController controller = VisionController.get();

        if (!controller.isOverriding() || !VisionConfig.get().settings.cameraPicking) {
            return;
        }

        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();

        if (!camera.isInitialized()) {
            return;
        }

        Vec3 eye = minecraft.player.getEyePosition(partialTick);
        Vec3 cameraPos = camera.getPosition();

        // Camera essentially at the eyes: vanilla already picked the right thing.
        if (cameraPos.distanceToSqr(eye) < 0.0025D) {
            return;
        }

        double reach = minecraft.gameMode.getPickRange();

        if (minecraft.gameMode.hasFarPickRange()) {
            reach = 6.0D;
        }

        Vec3 look = new Vec3(camera.getLookVector());
        Vec3 end = cameraPos.add(look.scale(reach));

        HitResult blockHit = minecraft.level.clip(
                new ClipContext(cameraPos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                minecraft.player,
                cameraPos,
                end,
                new AABB(cameraPos, end).inflate(1.0D),
                candidate -> !candidate.isSpectator() && candidate.isPickable(),
                reach * reach);

        HitResult best = blockHit;

        if (entityHit != null
                && (blockHit == null
                        || blockHit.getType() == HitResult.Type.MISS
                        || entityHit.getLocation().distanceToSqr(cameraPos) < blockHit.getLocation().distanceToSqr(cameraPos))) {
            best = entityHit;
        }

        if (best == null || best.getType() == HitResult.Type.MISS) {
            return;
        }

        // Never allow an interaction the player could not have made from their own eyes.
        if (best.getLocation().distanceToSqr(eye) > reach * reach + 1.0E-4D) {
            return;
        }

        minecraft.hitResult = best;

        if (best instanceof EntityHitResult hit) {
            Entity target = hit.getEntity();
            minecraft.crosshairPickEntity = target;
        } else if (best instanceof BlockHitResult) {
            minecraft.crosshairPickEntity = null;
        }
    }
}
