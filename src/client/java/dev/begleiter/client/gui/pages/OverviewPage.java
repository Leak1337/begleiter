package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class OverviewPage implements ControlPage {
    public Page page() { return Page.MAIN; }
    public Item icon() { return Items.COMPASS; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        var d = s.data();
        s.text(g, s.tr("gui.begleiter.overview.heading"), 110, 45, 188, ControlTheme.MUTED);
        s.text(g, s.tr("gui.begleiter.overview.task"), 110, 65, 188, ControlTheme.TEXT);
        s.paragraph(g, d.activity(), 110, 79, 188, 2, ControlTheme.ACCENT);
        s.text(g, s.tr("gui.begleiter.overview.inventory", d.usedSlots()), 110, 103, 188, ControlTheme.TEXT);
        drawBar(s, g, 116, d.usedSlots(), 36, ControlTheme.ACCENT);
        s.text(g, s.tr("gui.begleiter.overview.queue", d.queued(), d.hunger()), 110, 125, 188, ControlTheme.MUTED);
        drawBar(s, g, 138, d.hunger(), 20, 0xFFF0C674);
        s.text(g, s.tr("gui.begleiter.page.equipment"), 110, 149, 188, ControlTheme.MUTED);
        int[] slots = {19, 20, 21, 22, 24, 25};
        for (int i = 0; i < slots.length; i++) s.item(g, s.container().getSlot(slots[i]).getItem(), 112 + i * 28, 164, mx, my);
        if (d.hasProblem()) s.paragraph(g, d.problemText().getString(), 110, 190, 188, 2, 0xFFFFBA79);
        else s.feedback(g, 190);
    }
    private static void drawBar(CompanionScreen s, GuiGraphicsExtractor g, int y, int value, int max, int color) {
        int x = s.panelX() + 110;
        int top = s.panelY() + y;
        int filled = Math.round(188f * Math.clamp((float) value / max, 0f, 1f));
        g.fill(x, top, x + 188, top + 4, ControlTheme.LINE);
        if (filled > 0) g.fill(x, top, x + filled, top + 4, color);
    }
}
