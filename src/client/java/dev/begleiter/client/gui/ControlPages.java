package dev.begleiter.client.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.pages.*;
import net.minecraft.world.item.Items;

/** Stabile Seiten-IDs und Reihenfolge; Registrierung erfolgt einmalig, niemals beim Rendern. */
public final class ControlPages {
    private static final Map<Page, ControlPage> PAGES = new LinkedHashMap<>();
    static {
        register(new OverviewPage());
        register(new DutiesPage());
        register(new OrdersPage());
        register(new ActionsPage());
        register(new WorkPage());
        register(new AdventurePage());
        register(new BuildPage());
        register(new WaypointsPage());
        register(new InventoryPage());
        register(new EquipmentPage());
        register(new CombatPage());
        register(new StatusPage());
        register(new TalkPage());
        register(new PanelPage(Page.LOG, Items.BOOK, "gui.begleiter.log.heading", "gui.begleiter.log.hint"));
        register(new DebugPage());
    }
    private ControlPages() {}
    public static void register(ControlPage page) {
        if (PAGES.putIfAbsent(page.page(), page) != null)
            throw new IllegalArgumentException("Doppelte Control-Center-Seite: " + page.page());
    }
    public static List<ControlPage> all() { return List.copyOf(PAGES.values()); }
    public static ControlPage get(Page page) { return PAGES.get(page); }
}
