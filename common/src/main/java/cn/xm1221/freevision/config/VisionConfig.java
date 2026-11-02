package cn.xm1221.freevision.config;

import cn.xm1221.freevision.FreeVision;
import cn.xm1221.freevision.camera.CameraPose;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state: options plus the saved pose slots.
 *
 * <p>The file is plain JSON so players can share a pose by pasting six numbers, and so a modpack can
 * ship a default. Because it is hand-editable, everything read from it is <b>sanitised</b>: a pose at
 * a million blocks or at NaN would otherwise drive the per-frame clip ray through millions of blocks
 * and stall the client.
 */
public final class VisionConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static VisionConfig instance;

    public VisionSettings settings = new VisionSettings();

    /** Saved poses, indexed by slot. May be shorter than {@code maxSlots} (unused slots are null-safe). */
    public List<CameraPose> presets = new ArrayList<>();

    /** Index of the slot the toggle key engages. */
    public int activeSlot;

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

                if (parsed.presets == null) {
                    parsed.presets = new ArrayList<>();
                }

                parsed.presets.removeIf(pose -> pose == null);
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

    /** Validates everything that came off disk. Also never shrinks the player's saved poses. */
    private void sanitise() {
        if (this.settings == null) {
            this.settings = new VisionSettings();
        }

        if (this.settings.sanitize()) {
            FreeVision.LOG.warn("freevision.json contained out-of-range values; they were corrected");
        }

        if (this.presets == null) {
            this.presets = new ArrayList<>();
        }

        // A hand-written file with more poses than maxSlots is the player's data, not an error:
        // raise the limit instead of deleting their work.
        if (this.presets.size() > this.settings.maxSlots) {
            this.settings.maxSlots = Math.min(16, this.presets.size());
        }

        for (CameraPose pose : this.presets) {
            if (!Float.isFinite(pose.yawOffset) || !Float.isFinite(pose.pitchOffset) || !Float.isFinite(pose.roll)
                    || !Double.isFinite(pose.distance) || !Double.isFinite(pose.height) || !Double.isFinite(pose.side)) {
                FreeVision.LOG.warn("Discarding a perspective with non-finite numbers");
                pose.reset();
            }

            pose.clamp(this.settings.maxOffset);
        }

        if (this.presets.size() > 0) {
            this.activeSlot = Math.max(0, Math.min(this.activeSlot, this.presets.size() - 1));
        } else {
            this.activeSlot = 0;
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

    // ------------------------------------------------------------------ slots

    public CameraPose activePreset() {
        if (this.activeSlot < 0 || this.activeSlot >= this.presets.size()) {
            return null;
        }

        return this.presets.get(this.activeSlot);
    }

    public boolean hasPreset() {
        return this.activePreset() != null;
    }

    /** Stores a pose in the active slot, creating/growing the list as required. */
    public void storeActiveSlot(CameraPose pose) {
        this.activeSlot = Math.max(0, Math.min(this.activeSlot, Math.max(0, this.settings.maxSlots - 1)));

        while (this.presets.size() <= this.activeSlot) {
            this.presets.add(new CameraPose());
        }

        this.presets.set(this.activeSlot, pose.copy());
    }

    /** Advances to the next slot, wrapping around across the full configured slot count. */
    public int cycleSlot(int delta) {
        int slots = Math.max(1, this.settings.maxSlots);
        this.activeSlot = Math.floorMod(this.activeSlot + delta, slots);

        return this.activeSlot;
    }
}
