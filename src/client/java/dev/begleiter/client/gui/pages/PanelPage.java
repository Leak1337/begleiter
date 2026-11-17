package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;

/** Bestehende Bau-/Diagnosebedienung bleibt mit echten Item-Icons und Tooltips erhalten. Überschrift und Hinweis sind Übersetzungsschlüssel. */
public record PanelPage(Page page, Item icon, String headingKey, String hintKey) implements ControlPage {
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.text(g, s.tr(headingKey), 110, 41, 188, ControlTheme.MUTED);
        s.paragraph(g, s.tr(hintKey), 110, 169, 188, 2, ControlTheme.MUTED);
        s.feedback(g, 191);
    }
}
