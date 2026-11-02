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
 * <p>The file is read once at client init and rewritten on every accepted change (save, slot
 * switch, option edit). It is intentionally plain JSON so players can share a pose by pasting six
 * numbers, and so a modpack can ship a default.
 */
public final class VisionConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static VisionConfig instance;

    public VisionSettings settings = new VisionSettings();

    /** Saved poses, indexed by slot. May be shorter than {@link #activeSlot} after manual edits. */
    public List<CameraPose> presets = new ArrayList<>();

    /** Index of the slot that the toggle key engages. */
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
        } catch (IOException | JsonSyntaxException exception) {
            FreeVision.LOG.warn("Could not read config/freevision.json, using defaults", exception);
        }

        instance = config;
        return config;
    }

    public void save() {
        if (this.path == null) {
            try {
                this.path = Platform.getGameFolder().resolve("config").resolve("freevision.json");
            } catch (Throwable throwable) {
                FreeVision.LOG.warn("No config directory available", throwable);
                return;
            }
        }

        try {
            Files.createDirectories(this.path.getParent());
            Files.writeString(this.path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            FreeVision.LOG.warn("Could not write config/freevision.json", exception);
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
        this.activeSlot = Math.max(0, Math.min(this.activeSlot, this.settings.maxSlots - 1));

        while (this.presets.size() <= this.activeSlot) {
            this.presets.add(new CameraPose());
        }

        this.presets.set(this.activeSlot, pose.copy());
        this.trimSlots();
    }

    /** Advances to the next slot, wrapping around. Returns the new slot index. */
    public int cycleSlot(int delta) {
        int slots = Math.max(1, Math.min(this.settings.maxSlots, Math.max(this.presets.size(), 1)));
        this.activeSlot = Math.floorMod(this.activeSlot + delta, slots);
        return this.activeSlot;
    }

    public void deleteActiveSlot() {
        if (this.activeSlot >= 0 && this.activeSlot < this.presets.size()) {
            this.presets.remove(this.activeSlot);
        }

        if (this.activeSlot >= this.presets.size()) {
            this.activeSlot = Math.max(0, this.presets.size() - 1);
        }

        this.save();
    }

    private void trimSlots() {
        int max = Math.max(1, this.settings.maxSlots);

        while (this.presets.size() > max) {
            this.presets.remove(this.presets.size() - 1);
        }

        if (this.activeSlot >= this.presets.size()) {
            this.activeSlot = Math.max(0, this.presets.size() - 1);
        }
    }
}
