package dev.begleiter.client.gui;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionMenu;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

/**
 * Feste Oberflächentexte über die Minecraft-Sprache des Spielers (Schlüssel {@code gui.begleiter.*}).
 * Seiten und Aktionen werden über ihre stabilen IDs übersetzt; fehlt ein Schlüssel (z.B. neue
 * Serveraktion), bleibt der vom Server gelieferte Titel sichtbar statt eines Rohschlüssels.
 */
public final class GuiText {
    private GuiText() {}

    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    public static String page(CompanionMenu.Page page) {
        return orFallback("gui.begleiter.page." + page.key, page.title);
    }

    public static String action(CompanionActions.Action action) {
        return orFallback("gui.begleiter.action." + action.id(), action.title());
    }

    /** Anzeige des Modus aus dem Snapshot-Wert (0 folgen, 1 warten, 2 selbstständig, 3 Lager, 4–10 Daueraufgaben). */
    public static String mode(int mode) {
        return tr("gui.begleiter.mode." + Math.clamp(mode, 0, 10));
    }

    private static String orFallback(String key, String fallback) {
        return Language.getInstance().has(key) ? I18n.get(key) : fallback;
    }
}
