package dev.begleiter.client.gui;

import dev.begleiter.CompanionMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Per Tastatur erreichbarer Katalog-Eintrag mit echter Blockansicht statt eines Stellvertreter-Items. */
final class BuildCard extends Button {
    private final CompanionScreen screen;
    private final int index;
    BuildCard(CompanionScreen screen, int index, int x, int y) {
        super(x, y, screen.wideBuild() ? 150 : 92, screen.wideBuild() ? 86 : 49, Component.empty(), b -> screen.send(CompanionMenu.BUILD_PANEL_BUTTON
                + CompanionMenu.SCHEM_FIRST + index), DEFAULT_NARRATION);
        this.screen = screen;
        this.index = index;
    }
    @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        var stack = screen.container().getSlot(CompanionMenu.SCHEM_FIRST + index).getItem();
        active = !stack.isEmpty();
        if (!active) return;
        String title = stack.getHoverName().getString();
        setMessage(Component.literal(title));
        var tip = Component.literal(title);
        for (var line : stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE,
                net.minecraft.world.item.component.ItemLore.EMPTY).lines()) tip.append("\n").append(line);
        tip.append("\n").append(screen.tr("gui.begleiter.build.card.inspect"));
        setTooltip(Tooltip.create(tip));
        int x = getX(), y = getY();
        int viewport = height - (screen.wideBuild() ? 24 : 14);
        boolean hover = isHoveredOrFocused();
        g.fill(x, y, x + width, y + height, hover ? 0xFF263D4D : ControlTheme.PANEL);
        g.outline(x, y, width, height, hover ? ControlTheme.ACCENT : ControlTheme.LINE);
        g.fill(x + 1, y + viewport, x + width - 1, y + height - 1, 0xFF14212C);
        var model = screen.buildThumbnail(index);
        if (model != null) {
            model.prepareThumbnailFrame();
            var p = model.plan();
            float diameter = (float) Math.sqrt((double) p.sizeX() * p.sizeX()
                    + (double) p.sizeY() * p.sizeY() + (double) p.sizeZ() * p.sizeZ());
            g.guiRenderState.addPicturesInPictureState(BuildPreviewRenderer.card(index, model, x + 2, y + 2, width - 4, viewport - 3, (viewport - 8) / Math.max(1, diameter)));
        } else {
            var font = Minecraft.getInstance().font;
            g.text(font, screen.tr("gui.begleiter.build.card.loading"), x + 6, y + 14, ControlTheme.MUTED, false);
        }
        String prefix = Component.translatable("server.begleiter.blueprint.button", "").getString();
        if (title.startsWith(prefix)) title = title.substring(prefix.length());
        var font = Minecraft.getInstance().font;
        var lines = font.split(Component.literal(title), width - 10);
        for (int i = 0; i < Math.min(screen.wideBuild() ? 2 : 1, lines.size()); i++)
            g.text(font, lines.get(i), x + 5, y + viewport + 3 + i * 10, ControlTheme.TEXT, false);
    }
}
