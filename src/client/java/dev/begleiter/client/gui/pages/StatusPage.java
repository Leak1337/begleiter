package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

public final class StatusPage implements ControlPage {
    private int section;
    public Page page() { return Page.STATUS; }
    public Item icon() { return Items.APPLE; }
    public void init(CompanionScreen s) {
        String[] tabs = {"needs", "base", "knowledge"};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            String label = s.tr("gui.begleiter.status.tab." + tabs[i]);
            s.button(108 + i * 65, 40, 62, label, ItemStack.EMPTY, label, () -> section == selected,
                    () -> { section = selected; s.rebuildPage(); });
        }
    }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        if (section == 1) {
            int[] slots = {37, 38, 39, 40, 28, 29, 30, 31, 33};
            for (int i = 0; i < slots.length; i++)
                s.text(g, s.container().getSlot(slots[i]).getItem().getHoverName().getString(),
                        110, 69 + i * 14, 188, ControlTheme.TEXT);
            s.feedback(g, 193);
            return;
        }
        if (section == 2) {
            s.paragraph(g, s.container().getSlot(36).getItem().getHoverName().getString(),
                    110, 69, 188, 9, ControlTheme.TEXT);
            s.paragraph(g, s.container().getSlot(8).getItem().getHoverName().getString(),
                    110, 180, 188, 2, ControlTheme.MUTED);
            return;
        }
        s.text(g, s.tr("gui.begleiter.status.hunger", s.data().hunger()), 110, 64, 188, ControlTheme.TEXT);
        s.paragraph(g, s.data().needs(), 110, 83, 188, 5, ControlTheme.TEXT);
        s.heading(g, s.tr("gui.begleiter.status.progress"), 108, 142, 194);
        s.paragraph(g, s.data().stage().isEmpty() ? s.tr("gui.begleiter.no_plan") : s.data().stage(), 110, 156, 188, 2, ControlTheme.TEXT);
        s.paragraph(g, s.tr("gui.begleiter.status.environment", s.data().hazard()), 110, 185, 188, 2, ControlTheme.MUTED);
    }
}
