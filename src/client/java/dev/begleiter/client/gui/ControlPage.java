package dev.begleiter.client.gui;

import dev.begleiter.CompanionMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;

public interface ControlPage {
    CompanionMenu.Page page();
    Item icon();
    default void init(CompanionScreen screen) {}
    /** true, wenn sich Serverdaten geändert haben, die in Knopf-Beschriftungen stecken (Seite neu aufbauen). */
    default boolean stale(CompanionScreen screen) { return false; }
    void render(CompanionScreen screen, GuiGraphicsExtractor g, int mouseX, int mouseY);
}
