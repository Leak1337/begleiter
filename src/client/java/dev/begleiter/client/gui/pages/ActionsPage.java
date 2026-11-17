package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

public final class ActionsPage implements ControlPage {
    private int offset;
    private boolean strong;
    public Page page() { return Page.TASKS; }
    public Item icon() { return Items.WRITABLE_BOOK; }
    public void init(CompanionScreen s) {
        var actions = CompanionActions.all();
        for (int i = 0; i < 12 && offset + i < actions.size(); i++)
            s.actionButton(108 + i % 2 * 97, 61 + i / 2 * 22, 94, actions.get(offset + i), strong);
        s.button(108, 39, 60, s.tr("gui.begleiter.actions.back"), ItemStack.EMPTY, s.tr("gui.begleiter.actions.back.tooltip"), () -> false,
                () -> { offset = Math.max(0, offset - 12); s.rebuildPage(); });
        s.button(171, 39, 64, s.tr("gui.begleiter.amount", strong ? 64 : 16), ItemStack.EMPTY, s.tr("gui.begleiter.actions.amount.tooltip"), () -> strong,
                () -> { strong = !strong; s.rebuildPage(); });
        s.button(238, 39, 62, s.tr("gui.begleiter.actions.next"), ItemStack.EMPTY, s.tr("gui.begleiter.actions.next.tooltip"), () -> false,
                () -> { offset = offset + 12 < actions.size() ? offset + 12 : 0; s.rebuildPage(); });
    }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) { s.feedback(g, 194); }
}
