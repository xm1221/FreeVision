package cn.xm1221.freevision.client;

import cn.xm1221.freevision.camera.CameraPose;
import cn.xm1221.freevision.camera.VisionController;
import cn.xm1221.freevision.camera.VisionState;
import cn.xm1221.freevision.config.VisionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The whole in-game feedback surface: state chip, sculpt readout, save prompt and toasts.
 *
 * <p>Rendered as a tail injection into {@code Gui.render}, i.e. on top of the hotbar but below
 * screens. Nothing here is interactive, so it works fine in a multiplayer server that knows
 * nothing about the mod.
 */
public final class VisionHud {
    private static final int BLACK = 0xAA000000;
    private static final int ACCENT = 0xFF6FE3C1;
    private static final int TEXT = 0xFFE7EFEF;
    private static final int TEXT_DIM = 0xFF9FB0B4;

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
        VisionState state = controller.state();

        switch (state) {
            case SCULPTING -> renderSculptPanel(graphics, controller);
            case DRAFT -> renderDraftPrompt(graphics, controller);
            case ACTIVE -> renderActiveChip(graphics, config);
            default -> {
            }
        }

        renderToast(graphics, controller);
    }

    private static void renderActiveChip(GuiGraphics graphics, VisionConfig config) {
        int slot = config.activeSlot + 1;
        String title = Component.translatable("freevision.hud.active", slot).getString();
        String hint = Component.translatable("freevision.hud.active_hint").getString();

        drawPanel(graphics, 6, 6, Math.max(width(title), width(hint)), 2, title, hint);
    }

    private static void renderSculptPanel(GuiGraphics graphics, VisionController controller) {
        CameraPose pose = controller.sculptPose();

        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("freevision.hud.sculpting").getString());
        lines.add(String.format(Locale.ROOT, "%s %+.1f°   %s %+.1f°   %s %+.1f°",
                Component.translatable("freevision.hud.yaw").getString(), pose.yawOffset,
                Component.translatable("freevision.hud.pitch").getString(), pose.pitchOffset,
                Component.translatable("freevision.hud.roll").getString(), pose.roll));
        lines.add(String.format(Locale.ROOT, "%s %.2f   %s %+.2f   %s %+.2f",
                Component.translatable("freevision.hud.distance").getString(), pose.distance,
                Component.translatable("freevision.hud.height").getString(), pose.height,
                Component.translatable("freevision.hud.side").getString(), pose.side));
        lines.add(Component.translatable("freevision.hud.controls").getString());
        lines.add(Component.translatable("freevision.hud.release").getString());

        drawPanel(graphics, 6, 6, widthOf(lines), lines.size(), lines.toArray(new String[0]));
    }

    private static void renderDraftPrompt(GuiGraphics graphics, VisionController controller) {
        Minecraft minecraft = Minecraft.getInstance();
        String title = Component.translatable("freevision.hud.draft").getString();
        String hint = Component.translatable("freevision.hud.draft_hint").getString();
        int width = Math.max(width(title), width(hint)) + 16;
        int height = 34;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 96;

        graphics.fill(x, y, x + width, y + height, 0xD0000000);
        graphics.fill(x, y, x + 3, y + height, ACCENT);
        graphics.drawString(minecraft.font, title, x + 10, y + 7, ACCENT, true);
        graphics.drawString(minecraft.font, hint, x + 10, y + 19, TEXT_DIM, true);
    }

    private static void renderToast(GuiGraphics graphics, VisionController controller) {
        String hint = controller.hintKey();

        if (hint == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String text;

        if ("freevision.hint.slot".equals(hint)) {
            text = Component.translatable(hint, controller.hintSlot()).getString();
        } else {
            text = Component.translatable(hint).getString();
        }

        int width = minecraft.font.width(text) + 16;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() / 2 - 60;

        graphics.fill(x, y, x + width, y + 18, 0xB0000000);
        graphics.drawString(minecraft.font, text, x + 8, y + 5, TEXT, true);
    }

    // ------------------------------------------------------------------ drawing helpers

    private static int width(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    private static int widthOf(List<String> lines) {
        int max = 0;

        for (String line : lines) {
            max = Math.max(max, width(line));
        }

        return max;
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int textWidth, int lineCount, String... lines) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int lineHeight = 11;
        int width = textWidth + 14;
        int height = lineCount * lineHeight + 9;

        graphics.fill(x, y, x + width, y + height, BLACK);
        graphics.fill(x, y, x + 3, y + height, ACCENT);

        for (int index = 0; index < lines.length; index++) {
            int color = index == 0 ? ACCENT : (index >= lines.length - 2 ? TEXT_DIM : TEXT);
            graphics.drawString(font, lines[index], x + 8, y + 5 + index * lineHeight, color, true);
        }
    }

    /** Exposed for the debug overlay: a compact one-line dump of the live rig. */
    public static String debugLine() {
        VisionController controller = VisionController.get();
        CameraPose pose = controller.sculptPose();

        return String.format(Locale.ROOT, "FreeVision %s yaw=%.1f pitch=%.1f roll=%.1f d=%.2f h=%.2f s=%.2f blend=%.2f",
                controller.state(),
                pose.yawOffset, pose.pitchOffset, pose.roll, pose.distance, pose.height, pose.side,
                Mth.clamp(controller.blendProgress(), 0.0F, 1.0F));
    }
}
