package cn.xm1221.freevision.config;

import cn.xm1221.freevision.FreeVision;
import cn.xm1221.freevision.camera.CameraPose;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persistent state: options plus the single saved perspective.
 *
 * <p>The file is plain JSON so a perspective can be shared by copying a few numbers, and so a
 * modpack can ship a default. Because it is hand-editable, everything read from it is
 * <b>sanitised</b>: a pose at a million blocks or at NaN would otherwise drive the per-frame clip
 * ray through millions of blocks and stall the client.
 *
 * <p>The multi-slot builds stored a {@code presets} list; those fields are still read once and
 * folded into {@link #pose} so nobody loses their saved camera on upgrade.
 */
public final class VisionConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static VisionConfig instance;

    public VisionSettings settings = new VisionSettings();

    /** The saved perspective, or {@code null} when the player has not saved one yet. */
    public CameraPose pose;

    // Legacy multi-slot storage. Read for migration, never written again.
    private List<CameraPose> presets;
    private Integer activeSlot;

    private transient Path path;

    public static VisionConfig get() {
        if (instance == null) {
            instance = new VisionConfig();
        }

        return instance;
    }

    private VisionConfig() {
    }

    /** Loads (or creates) {@code config/freevision.json}. Safe to call more than once. */
    public static VisionConfig load() {
        VisionConfig config = new VisionConfig();

        try {
            config.path = Platform.getGameFolder().resolve("config").resolve("freevision.json");

            if (Files.notExists(config.path)) {
                config.save();
                instance = config;

                return config;
            }

            String json = Files.readString(config.path, StandardCharsets.UTF_8);
            VisionConfig parsed = GSON.fromJson(json, VisionConfig.class);

            if (parsed != null) {
                if (parsed.settings == null) {
                    parsed.settings = new VisionSettings();
                }

                parsed.path = config.path;
                config = parsed;
            }
        } catch (IOException | RuntimeException exception) {
            FreeVision.LOG.warn("Could not read config/freevision.json, using defaults", exception);
        }

        config.sanitise();

        instance = config;

        return config;
    }

    /** Validates everything that came off disk and folds the legacy slot list into {@link #pose}. */
    private void sanitise() {
        if (this.settings == null) {
            this.settings = new VisionSettings();
        }

        if (this.settings.sanitize()) {
            FreeVision.LOG.warn("freevision.json contained out-of-range values; they were corrected");
        }

        if (this.pose == null && this.presets != null && !this.presets.isEmpty()) {
            int active = this.activeSlot == null ? 0 : this.activeSlot;
            int index = Math.max(0, Math.min(active, this.presets.size() - 1));
            CameraPose legacy = this.presets.get(index);

            if (legacy != null) {
                FreeVision.LOG.info("Migrating perspective slot {} to the single saved perspective", index + 1);
                this.pose = legacy.copy();
            }
        }

        this.presets = null;
        this.activeSlot = null;

        if (this.pose != null) {
            if (!Float.isFinite(this.pose.yawOffset) || !Float.isFinite(this.pose.pitchOffset)
                    || !Float.isFinite(this.pose.roll) || !Double.isFinite(this.pose.distance)
                    || !Double.isFinite(this.pose.height) || !Double.isFinite(this.pose.side)) {
                FreeVision.LOG.warn("Discarding a perspective with non-finite numbers");
                this.pose = null;

                return;
            }

            this.pose.clamp(this.settings.maxOffset);
        }
    }

    /** @return {@code false} when the file could not be written (disk full, read-only, ...) */
    public boolean save() {
        if (this.path == null) {
            try {
                this.path = Platform.getGameFolder().resolve("config").resolve("freevision.json");
            } catch (Throwable throwable) {
                FreeVision.LOG.warn("No config directory available", throwable);

                return false;
            }
        }

        try {
            Files.createDirectories(this.path.getParent());
            Files.writeString(this.path, GSON.toJson(this), StandardCharsets.UTF_8);

            return true;
        } catch (IOException exception) {
            FreeVision.LOG.warn("Could not write config/freevision.json", exception);

            return false;
        }
    }

    // ------------------------------------------------------------------ the saved perspective

    public CameraPose pose() {
        return this.pose;
    }

    public boolean hasPose() {
        return this.pose != null;
    }

    public void storePose(CameraPose newPose) {
        this.pose = newPose.copy();
    }

    public void clearPose() {
        this.pose = null;
    }
}
