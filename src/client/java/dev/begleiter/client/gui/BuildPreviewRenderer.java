package dev.begleiter.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** Zeichnet über Minecrafts Bild-im-Bild-Renderer der Oberfläche, mit eigenem Tiefenpuffer. */
public final class BuildPreviewRenderer<T extends BuildPreviewRenderer.ModelState> extends PictureInPictureRenderer<T> {
    private final Class<T> stateClass;
    private T rendered;
    private Object resourceModels;
    private int renderedRevision;
    private long renderedAt;
    public BuildPreviewRenderer(Class<T> stateClass) { this.stateClass = stateClass; }
    public interface ModelState extends PictureInPictureRenderState {
        BuildPreviewModel model();
        default float yaw() { return 135; }
        default float pitch() { return 30; }
        @Override default ScreenRectangle bounds() {
            return PictureInPictureRenderState.getBounds(x0(), y0(), x1(), y1(), scissorArea());
        }
    }
    // Jede sichtbare Karte braucht eine eigene Textur: Vanilla zeichnet alle erst, nachdem alle Modelle gerendert sind

    public record State(BuildPreviewModel model, float yaw, float pitch, int x0, int y0, int x1, int y1,
                        float scale, ScreenRectangle scissorArea) implements ModelState {
        @Override public ScreenRectangle bounds() { return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea); }
    }
    public record Card0(BuildPreviewModel model, int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) implements ModelState {}
    public record Card1(BuildPreviewModel model, int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) implements ModelState {}
    public record Card2(BuildPreviewModel model, int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) implements ModelState {}
    public record Card3(BuildPreviewModel model, int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea) implements ModelState {}
    public static ModelState card(int index, BuildPreviewModel model, int x, int y, int width, int height, float scale) {
        var clip = new ScreenRectangle(x, y, width, height);
        return switch (index) {
            case 0 -> new Card0(model, x, y, x + width, y + height, scale, clip);
            case 1 -> new Card1(model, x, y, x + width, y + height, scale, clip);
            case 2 -> new Card2(model, x, y, x + width, y + height, scale, clip);
            case 3 -> new Card3(model, x, y, x + width, y + height, scale, clip);
            default -> throw new IllegalArgumentException("Invalid catalogue card");
        };
    }
    @Override public Class<T> getRenderStateClass() { return stateClass; }
    @Override protected boolean textureIsReadyToBlit(T state) {
        return rendered != null && rendered.model() == state.model() && rendered.yaw() == state.yaw()
                && rendered.pitch() == state.pitch() && rendered.scale() == state.scale()
                && (renderedRevision == state.model().revision()
                    || !state.model().ready() && System.nanoTime() - renderedAt < 100_000_000L)
                && resourceModels == Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }
    @Override protected String getTextureLabel() { return "begleiter-build-preview-" + stateClass.getSimpleName(); }
    @Override protected float getTranslateY(int height, int guiScale) { return height / 2f; }
    @Override protected void renderToTexture(T state, PoseStack pose, SubmitNodeCollector collector) {
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        // Die Oberflächen-Kamera kehrt Z schon um: zurückdrehen und dabei „oben“ der Welt auf „oben“ am Bildschirm legen
        pose.scale(1, -1, -1);
        pose.rotateDegrees(Axis.XP, state.pitch());
        pose.rotateDegrees(Axis.YP, state.yaw());
        var plan = state.model().plan();
        pose.translate(-plan.sizeX() / 2f, -plan.sizeY() / 2f, -plan.sizeZ() / 2f);
        state.model().submit(pose, collector);
        rendered = state;
        renderedRevision = state.model().revision();
        renderedAt = System.nanoTime();
        resourceModels = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }
}
