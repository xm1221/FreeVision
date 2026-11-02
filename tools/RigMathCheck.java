import java.util.Random;

/**
 * Standalone numeric check for the FreeVision rig maths (mirrors CameraPose exactly).
 * Run with: java RigMathCheck.java   (JDK 11+ single-file source execution)
 */
public final class RigMathCheck {
    record Pose(float yawOffset, float pitchOffset, float roll, double distance, double height, double side) {}

    record V3(double x, double y, double z) {
        V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
        V3 add(double dx, double dy, double dz) { return new V3(x + dx, y + dy, z + dz); }
        V3 scale(double s) { return new V3(x * s, y * s, z * s); }
        V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
        double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
        double length() { return Math.sqrt(dot(this)); }
    }

    static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    static V3 back(float cameraYaw) {
        double r = Math.toRadians(cameraYaw);
        return new V3(Math.sin(r), 0.0D, -Math.cos(r));
    }

    static V3 left(float cameraYaw) {
        double r = Math.toRadians(cameraYaw);
        return new V3(Math.cos(r), 0.0D, Math.sin(r));
    }

    static Pose fromWorld(V3 eye, V3 cameraPos, float viewYaw, float viewPitch, float cameraYaw, float cameraPitch) {
        V3 b = back(cameraYaw);
        V3 l = left(cameraYaw);
        V3 d = cameraPos.sub(eye);
        return new Pose(
                wrapDegrees(cameraYaw - viewYaw),
                cameraPitch - viewPitch,
                0.0F,
                d.dot(b),
                d.y,
                d.dot(l));
    }

    static V3 position(V3 eye, float viewYaw, Pose pose) {
        float cameraYaw = viewYaw + pose.yawOffset();
        V3 b = back(cameraYaw);
        V3 l = left(cameraYaw);
        return eye.add(b.scale(pose.distance())).add(l.scale(pose.side())).add(0.0D, pose.height(), 0.0D);
    }

    static float lerpAngle(float from, float to, float delta) {
        float difference = wrapDegrees(to - from);
        return from + difference * delta;
    }

    public static void main(String[] args) {
        Random random = new Random(20261102L);
        int failures = 0;
        double worst = 0.0D;

        // 1. round trip: world camera -> rig -> world camera
        for (int i = 0; i < 200000; i++) {
            V3 eye = new V3(random.nextDouble() * 2e6 - 1e6, random.nextDouble() * 512 - 64, random.nextDouble() * 2e6 - 1e6);
            float viewYaw = (float) (random.nextDouble() * 720 - 360);
            float viewPitch = (float) (random.nextDouble() * 178 - 89);
            float cameraYaw = viewYaw + (float) (random.nextDouble() * 90 - 45);
            float cameraPitch = (float) (random.nextDouble() * 178 - 89);
            V3 cameraPos = new V3(eye.x() + random.nextGaussian() * 8, eye.y() + random.nextGaussian() * 4,
                    eye.z() + random.nextGaussian() * 8);

            Pose pose = fromWorld(eye, cameraPos, viewYaw, viewPitch, cameraYaw, cameraPitch);
            V3 rebuilt = position(eye, viewYaw, pose);

            double error = rebuilt.sub(cameraPos).length();
            worst = Math.max(worst, error);

            if (error > 1.0E-6D) {
                failures++;
                if (failures < 4) {
                    System.out.println("round-trip error " + error + " pose=" + pose + " expected=" + cameraPos + " got=" + rebuilt);
                }
            }

            float rebuiltYaw = wrapDegrees(viewYaw + pose.yawOffset());
            if (Math.abs(wrapDegrees(rebuiltYaw - cameraYaw)) > 1.0E-3F) {
                failures++;
            }
        }

        // 2. orbit property: a pure yaw change must keep the distance to the eye constant
        V3 eye = new V3(100.0D, 70.0D, -30.0D);
        Pose pose = new Pose(35.0F, -20.0F, 0.0F, 4.0D, 1.5D, -1.0D);
        V3 base = position(eye, 0.0F, pose);

        for (int deg = 0; deg < 360; deg += 7) {
            V3 turned = position(eye, deg, pose);
            double radiusBase = Math.hypot(base.x() - eye.x(), base.z() - eye.z());
            double radiusTurned = Math.hypot(turned.x() - eye.x(), turned.z() - eye.z());

            if (Math.abs(radiusBase - radiusTurned) > 1.0E-6D) {
                failures++;
                System.out.println("orbit radius drifted at " + deg + ": " + radiusBase + " vs " + radiusTurned);
                break;
            }

            if (Math.abs(turned.y() - base.y()) > 1.0E-9D) {
                failures++;
                System.out.println("height changed while only yaw changed");
                break;
            }
        }

        // 3. shortest-arc yaw blend never takes the long way round
        float half = lerpAngle(350.0F, 10.0F, 0.5F);
        float normalised = wrapDegrees(half);
        if (Math.abs(normalised) > 1.0E-3F) {
            failures++;
            System.out.println("lerpAngle took the long way: " + half);
        }

        System.out.println("worst round-trip error = " + worst);
        System.out.println(failures == 0 ? "ALL RIG MATH CHECKS PASSED" : (failures + " FAILURES"));
        if (failures != 0) {
            System.exit(1);
        }
    }
}
