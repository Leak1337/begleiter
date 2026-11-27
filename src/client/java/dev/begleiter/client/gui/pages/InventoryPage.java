package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class InventoryPage implements ControlPage {
    @Override public boolean quickBar() { return false; }
    public Page page() { return Page.INVENTORY; }
    public Item icon() { return Items.CHEST; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.text(g, s.tr("gui.begleiter.inventory.companion", s.data().usedSlots()), 122, 41, 176, ControlTheme.MUTED);
        s.text(g, s.tr("gui.begleiter.inventory.player"), 122, 130, 176, ControlTheme.MUTED);
        s.text(g, s.tr("gui.begleiter.inventory.hint"), 122, 225, 176, ControlTheme.MUTED);
    }
}
