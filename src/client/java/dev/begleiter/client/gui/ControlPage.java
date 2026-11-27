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
    /** Leiste Folgen/Warten/Frei am unteren Rand; Seiten mit eigener Bedienung dort schalten sie ab. */
    default boolean quickBar() { return true; }
    /** Klick in selbst gezeichnete Flächen (relative GUI-Koordinaten); true = verbraucht. */
    default boolean mouseClicked(CompanionScreen screen, double x, double y, int button) { return false; }
    /** Mausrad über der Seite (relative GUI-Koordinaten); true = verbraucht. */
    default boolean mouseScrolled(CompanionScreen screen, double x, double y, double amount) { return false; }
}
