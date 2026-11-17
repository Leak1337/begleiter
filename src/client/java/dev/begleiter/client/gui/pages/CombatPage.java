package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class CombatPage implements ControlPage {
    public Page page() { return Page.COMBAT; }
    public Item icon() { return Items.IRON_SWORD; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.text(g, s.tr("gui.begleiter.combat.heading"), 110, 45, 188, ControlTheme.MUTED);
        s.text(g, s.tr("gui.begleiter.combat.target"), 110, 67, 188, ControlTheme.MUTED);
        s.paragraph(g, s.data().target(), 110, 82, 188, 2, ControlTheme.ACCENT);
        s.item(g, s.container().getSlot(24).getItem(), 112, 116, mx, my);
        s.item(g, s.container().getSlot(25).getItem(), 144, 116, mx, my);
        s.paragraph(g, s.tr("gui.begleiter.combat.hint"), 110, 150, 188, 2, ControlTheme.TEXT);
    }
    public void init(CompanionScreen s) {
        for (int i = 0; i < 3; i++)
            s.actionButton(108 + i * 65, 176, 62, dev.begleiter.CompanionActions.get(33 + i), false);
    }
}
