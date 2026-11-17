package dev.begleiter.client.gui;

import dev.begleiter.CompanionBuildPreview;
import dev.begleiter.CompanionMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.world.item.ItemStack;

/** Große Vorschau eines Katalog-Eintrags im offenen Menü; Escape führt zurück zum Katalog. */
final class BuildPreviewPanel {
    private final CompanionBuildPreview preview;
    private final BuildPreviewModel model;
    private float yaw = 135, pitch = 30, zoom = 1;
    /** Große Schematics brauchen mehr Vergrößerung, sonst ist ein Block kaum ein Pixel groß. */
    private final float maxZoom;
    BuildPreviewPanel(CompanionBuildPreview preview, BuildPreviewModel model) {
        this.preview = preview;
        this.model = model;
        var p = preview.plan();
        maxZoom = Math.max(4f, (float) Math.sqrt((double) p.sizeX() * p.sizeX() + (double) p.sizeY() * p.sizeY()
                + (double) p.sizeZ() * p.sizeZ()) / 12f);
    }
    BuildPreviewModel model() { return model; }
    void init(CompanionScreen s) {
        int bottom = s.wideBuild() ? 268 : 184, footer = s.wideBuild() ? 297 : 211;
        button(s, 10, bottom, 30, "↶", "rotate", () -> yaw -= 30);
        button(s, 43, bottom, 30, "↷", "rotate", () -> yaw += 30);
        button(s, 80, bottom, 30, "−", "zoom", () -> zoom = Math.clamp(zoom / 1.2f, .4f, maxZoom));
        button(s, 113, bottom, 30, "+", "zoom", () -> zoom = Math.clamp(zoom * 1.2f, .4f, maxZoom));
        button(s, 150, bottom, s.wideBuild() ? 268 : 148, s.tr("gui.begleiter.build.preview.reset"), "reset", () -> { yaw = 135; pitch = 30; zoom = 1; });
        button(s, 10, footer, s.wideBuild() ? 198 : 138, s.tr("gui.begleiter.build.preview.back"), "back", s::closeBuildPreview);
        button(s, s.wideBuild() ? 215 : 155, footer, s.wideBuild() ? 203 : 143, s.tr("gui.begleiter.build.preview.place"), "place", () -> {
            s.send(CompanionMenu.BUILD_PREVIEW_PLACE);
            s.closeBuildPreview();
        });
    }
    private void button(CompanionScreen s, int x, int y, int width, String label, String tip, Runnable action) {
        s.button(x, y, width, label, ItemStack.EMPTY, s.tr("gui.begleiter.build.preview." + tip + ".tooltip"), () -> false, action);
    }
    void render(CompanionScreen s, GuiGraphicsExtractor g) {
        model.prepareFrame();
        int w = s.wideBuild() ? 408 : 288, h = s.wideBuild() ? 208 : 128;
        s.text(g, preview.title().getString(), 10, 8, w, ControlTheme.TEXT);
        var p = preview.plan();
        s.text(g, s.tr("gui.begleiter.build.preview.size", p.sizeX(), p.sizeY(), p.sizeZ(), p.blocks().size()),
                10, 23, w, ControlTheme.ACCENT);
        int x = s.panelX() + 10, y = s.panelY() + 39;
        g.fill(x, y, x + w, y + h, 0xFF101A24);
        g.outline(x, y, w, h, ControlTheme.LINE);
        float diameter = (float) Math.sqrt((double) p.sizeX() * p.sizeX() + (double) p.sizeY() * p.sizeY() + (double) p.sizeZ() * p.sizeZ());
        g.guiRenderState.addPicturesInPictureState(new BuildPreviewRenderer.State(model, yaw, pitch,
                x + 1, y + 1, x + w - 1, y + h - 1, (h - 16) / Math.max(1, diameter) * zoom,
                new ScreenRectangle(x + 1, y + 1, w - 2, h - 2)));
        s.text(g, s.tr("gui.begleiter.build.preview.controls"), 10, 44 + h, w, ControlTheme.MUTED);
        if (!model.ready()) s.text(g, s.tr("gui.begleiter.build.preview.loading", model.progress()),
                18, 47, w - 16, ControlTheme.ACCENT);
    }
    boolean contains(CompanionScreen s, double x, double y) {
        return x >= s.panelX() + 10 && x < s.panelX() + (s.wideBuild() ? 418 : 298) && y >= s.panelY() + 39 && y < s.panelY() + (s.wideBuild() ? 247 : 167);
    }
    void drag(double dx, double dy) { yaw = (yaw + (float) dx) % 360; pitch = Math.clamp(pitch + (float) dy, -85, 85); }
    void scroll(double amount) { zoom = Math.clamp(zoom * (float) Math.pow(1.15, amount), .4f, maxZoom); }
}
