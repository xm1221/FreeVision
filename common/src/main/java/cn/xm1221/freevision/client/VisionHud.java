package cn.xm1221.freevision.client;

import cn.xm1221.freevision.camera.CameraPose;
import cn.xm1221.freevision.camera.VisionController;
import cn.xm1221.freevision.camera.VisionState;
import cn.xm1221.freevision.config.VisionConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game feedback surface: the adjusting panel and short status toasts.
 *
 * <p>There is deliberately no permanent indicator when a custom perspective is active - the player
 * can see that the camera moved, and a permanent HUD element would only be clutter. Feedback is
 * transient: a panel while adjusting (where the numbers matter) and a short toast when something
 * changes.
 *
 * <p>Every key mentioned in the text comes from the live {@link KeyMapping}, so a player who rebinds
 * anything is told the key they actually have. Nothing about the binding is hard-coded.
 *
 * <p>Lines wrap instead of clipping, the panel and the toast are both clamped to the screen, and the
 * toast sits above the hotbar rather than over the boss bar.
 */
public final class VisionHud {
    private static final int PANEL_BG = 0xD90B1013;
    private static final int TOAST_BG = 0xE60B1013;
    private static final int ACCENT = 0xFF6FE3C1;
    private static final int TEXT = 0xFFF2F7F7;
    private static final int TEXT_DIM = 0xFFAEBFC4;
    private static final int LINE_HEIGHT = 11;

    private VisionHud() {
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        VisionConfig config = VisionConfig.get();

        if (!config.settings.showHud) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }

        VisionController controller = VisionController.get();
        boolean sculpting = controller.state() == VisionState.SCULPTING;

        if (sculpting && config.settings.showPanel) {
            renderPanel(graphics, controller, config);
        } else if (config.settings.showToasts) {
            renderToast(graphics, controller);
        }
    }

    private static void renderPanel(GuiGraphics graphics, VisionController controller, VisionConfig config) {
        CameraPose pose = controller.sculptPose();
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        List<String> lines = new ArrayList<>();

        lines.add(tr("freevision.hud.sculpting"));
        lines.add(Component.translatable("freevision.hud.slot",
                config.activeSlot + 1, Math.max(1, config.settings.maxSlots)).getString());
        lines.add(String.format(Locale.ROOT, "%s %s   %s %+.1f°   %s %+.1f°   %s %+.1f°",
                tr("freevision.hud.mode"),
                tr(pose.absolute ? "freevision.hud.mode.absolute" : "freevision.hud.mode.relative"),
                tr(pose.absolute ? "freevision.hud.world_yaw" : "freevision.hud.yaw"), pose.yawOffset,
                tr(pose.absolute ? "freevision.hud.world_pitch" : "freevision.hud.pitch"), pose.pitchOffset,
                tr("freevision.hud.roll"), pose.roll));
        lines.add(String.format(Locale.ROOT, "%s %.2f   %s %+.2f   %s %+.2f",
                tr("freevision.hud.distance"), pose.distance,
                tr("freevision.hud.height"), pose.height,
                tr("freevision.hud.side"), pose.side));
        lines.add(Component.translatable("freevision.hud.controls_move",
                key(minecraft.options.keyUp), key(minecraft.options.keyLeft),
                key(minecraft.options.keyDown), key(minecraft.options.keyRight),
                key(minecraft.options.keyJump), key(minecraft.options.keyShift),
                key(minecraft.options.keySprint)).getString());
        lines.add(Component.translatable("freevision.hud.controls_look",
                key(VisionKeybinds.lookUp), key(VisionKeybinds.lookDown),
                key(VisionKeybinds.lookLeft), key(VisionKeybinds.lookRight)).getString());
        lines.add(Component.translatable("freevision.hud.controls_rig",
                key(VisionKeybinds.rollLeft), key(VisionKeybinds.rollRight),
                tr("freevision.hud.scroll_word"), key(VisionKeybinds.dollyIn), key(VisionKeybinds.dollyOut),
                key(VisionKeybinds.reset), key(VisionKeybinds.lock)).getString());
        lines.add(Component.translatable("freevision.hud.finish",
                key(VisionKeybinds.edit), key(VisionKeybinds.discard)).getString());

        int maxWidth = Math.max(80, graphics.guiWidth() - 24);
        List<FormattedCharSequence> rendered = new ArrayList<>();
        int widest = 0;

        for (String line : lines) {
            List<FormattedCharSequence> wrapped = font.split(Component.literal(line), maxWidth - 16);

            for (FormattedCharSequence sequence : wrapped) {
                rendered.add(sequence);
                widest = Math.max(widest, font.width(sequence));
            }
        }

        int x = 8;
        int y = 8;
        int width = Math.min(maxWidth, widest + 16);
        int height = rendered.size() * LINE_HEIGHT + 10;

        graphics.fill(x, y, x + width, y + height, PANEL_BG);
        graphics.fill(x, y, x + 3, y + height, ACCENT);

        for (int index = 0; index < rendered.size(); index++) {
            // The first three rows are the readout; the rest are key hints.
            int color = index == 0 ? ACCENT : (index >= rendered.size() - 3 ? TEXT_DIM : TEXT);
            graphics.drawString(font, rendered.get(index), x + 9, y + 6 + index * LINE_HEIGHT, color, true);
        }
    }

    private static void renderToast(GuiGraphics graphics, VisionController controller) {
        String hint = controller.hintKey();

        if (hint == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String text = Component.translatable(hint, controller.hintArgs()).getString();
        int maxWidth = Math.max(60, graphics.guiWidth() - 16);
        int width = Math.min(maxWidth, minecraft.font.width(text) + 18);
        int x = Math.max(4, (graphics.guiWidth() - width) / 2);
        // Above the hotbar, away from the boss bar and /title handling.
        int y = Math.max(8, graphics.guiHeight() - 78);

        graphics.fill(x, y, x + width, y + 19, TOAST_BG);
        graphics.fill(x, y, x + 3, y + 19, ACCENT);
        graphics.drawString(minecraft.font, text, x + 10, y + 6, TEXT, true);
    }

    // ------------------------------------------------------------------ helpers

    private static String tr(String translationKey) {
        return Component.translatable(translationKey).getString();
    }

    private static String key(KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
    }
}
