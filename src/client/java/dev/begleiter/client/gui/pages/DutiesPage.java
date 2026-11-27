package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Grundmodi und sieben wiederkehrende Jobs; die Karte liegt direkt bei den Aufgaben. */
public final class DutiesPage implements ControlPage {
    @Override public boolean quickBar() { return false; }
    private int tab;
    private static final int[][] ACTIONS = {{2, 0, 1, 40, 41}, {37, 38, 43, 42, 39}};
    private static final String[][] KEYS = {{"free", "follow", "stay", "explore", "base"}, {"farm", "animals", "fetch", "storage", "trade"}};
    public Page page() { return Page.DUTIES; }
    public Item icon() { return Items.CLOCK; }
    public void init(CompanionScreen s) {
        for (int i = 0; i < 2; i++) {
            int index = i;
            s.button(108 + i * 98, 43, 94, s.tr("gui.begleiter.duties.tab." + i), ItemStack.EMPTY,
                    s.tr(i == 0 ? "gui.begleiter.duties.heading" : "gui.begleiter.duties.ongoing"), () -> tab == index, () -> { tab = index; s.rebuildPage(); });
        }
        for (int i = 0; i < ACTIONS[tab].length; i++) {
            var action = CompanionActions.get(ACTIONS[tab][i]);
            String key = "gui.begleiter.duties." + KEYS[tab][i];
            s.button(108, 68 + i * 23, 192, s.tr(key), new ItemStack(dev.begleiter.CompanionCraft.item(action.icon())),
                    s.tr(key + ".tooltip"), () -> s.data().mode() == action.mode(),
                    () -> s.send(CompanionMenu.ACTION_BUTTON + action.button()));
        }
        s.actionButton(108, 186, 192, CompanionActions.get(44), false);
    }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) { s.feedback(g, 213); }
}
