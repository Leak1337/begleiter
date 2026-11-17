package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import dev.begleiter.client.gui.ControlTheme;
import dev.begleiter.client.gui.GuiText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Seite zwischen Grundverhalten und Entscheidungsmodul, mit dem serverweiten Schalter. */
public final class DebugPage implements ControlPage {
    /** Zustand, mit dem der Knopf beschriftet wurde. */
    private boolean shownEnabled;

    @Override public Page page() { return Page.DEBUG; }
    @Override public Item icon() { return Items.COMPARATOR; }

    @Override public void init(CompanionScreen s) {
        shownEnabled = s.data().intelligenceEnabled();
        s.button(232, 81, 66, s.tr(shownEnabled ? "gui.begleiter.on" : "gui.begleiter.off"), ItemStack.EMPTY,
                s.tr("gui.begleiter.debug.toggle.tooltip"),
                () -> s.data().intelligenceEnabled(), () -> s.send(CompanionMenu.INTELLIGENCE_TOGGLE));
    }

    @Override public boolean stale(CompanionScreen s) {
        return s.data().intelligenceEnabled() != shownEnabled;
    }

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        var data = s.data();
        s.text(g, s.tr("gui.begleiter.debug.heading"), 110, 45, 188, ControlTheme.MUTED);
        s.text(g, s.tr("gui.begleiter.debug.vanilla"), 110, 66, 120, ControlTheme.TEXT);
        s.text(g, s.tr("gui.begleiter.debug.active"), 232, 66, 66, ControlTheme.ACCENT);
        s.text(g, s.tr("gui.begleiter.debug.intelligence"), 110, 86, 120, ControlTheme.TEXT);
        s.text(g, s.tr("gui.begleiter.debug.mode"), 110, 111, 188, ControlTheme.MUTED);
        s.paragraph(g, GuiText.mode(data.mode()) + " · " + data.activity(), 110, 126, 188, 2, ControlTheme.TEXT);
        s.text(g, s.tr("gui.begleiter.debug.decision"), 110, 153, 188, ControlTheme.MUTED);
        s.paragraph(g, data.stage().isEmpty() ? s.tr("gui.begleiter.no_plan") : data.stage(),
                110, 168, 188, 2, ControlTheme.TEXT);
        s.paragraph(g, s.tr("gui.begleiter.debug.switch.hint"), 110, 194, 188, 2, ControlTheme.MUTED);
    }
}
