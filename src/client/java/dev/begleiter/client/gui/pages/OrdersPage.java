package dev.begleiter.client.gui.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenuCommands;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import dev.begleiter.client.gui.ControlTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Aufträge ohne Chat: Item suchen, Menge wählen und bestellen, herstellen, einlagern oder als Vorrat halten.
 * Die Auswahl teilen sich alle Seiten (z. B. für Arbeitsbudgets).
 */
public final class OrdersPage implements ControlPage {
    private static final int GRID_X = 108, GRID_Y = 59, CELL = 21, COLS = 9, ROWS = 2;
    private static final List<Item> COMMON = List.of(Items.TORCH, Items.OAK_LOG, Items.OAK_PLANKS, Items.COBBLESTONE,
            Items.IRON_INGOT, Items.COAL, Items.BREAD, Items.COOKED_BEEF, Items.STICK, Items.CRAFTING_TABLE, Items.FURNACE,
            Items.CHEST, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.GLASS, Items.BED.white(), Items.ARROW, Items.SHIELD);
    private String query = "";
    private List<Item> results = COMMON;
    private int scroll;
    private int tab;

    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.ORDERS; }
    @Override public Item icon() { return Items.BUNDLE; }
    @Override public boolean quickBar() { return false; }

    @Override public void init(CompanionScreen s) {
        s.editBox(108, 40, 194, 16, s.tr("gui.begleiter.orders.search"), query, 50, value -> {
            query = value;
            scroll = 0;
            results = search(value);
        }, () -> { if (!results.isEmpty()) s.selectItem(results.getFirst()); });
        s.button(190, 102, 14, 16, "-", ItemStack.EMPTY, s.tr("gui.begleiter.orders.amount.tooltip"), () -> false,
                () -> setAmount(s, s.selectedAmount() - step(s.selectedAmount(), false)));
        amountBox = s.editBox(206, 102, 24, 16, "", String.valueOf(s.selectedAmount()), 3, value -> {
            String digits = value.replaceAll("\\D", "");
            if (!digits.isEmpty()) s.selectAmount(Integer.parseInt(digits));
        }, null);
        s.button(232, 102, 14, 16, "+", ItemStack.EMPTY, s.tr("gui.begleiter.orders.amount.tooltip"), () -> false,
                () -> setAmount(s, s.selectedAmount() + step(s.selectedAmount(), true)));
        s.button(249, 102, 25, 16, "16", ItemStack.EMPTY, s.tr("gui.begleiter.amount", 16), () -> s.selectedAmount() == 16,
                () -> setAmount(s, 16));
        s.button(277, 102, 25, 16, "64", ItemStack.EMPTY, s.tr("gui.begleiter.amount", 64), () -> s.selectedAmount() == 64,
                () -> setAmount(s, 64));
        String[] tabs = {"order", "stock"};
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            String label = s.tr("gui.begleiter.orders.tab." + tabs[i]);
            s.button(108 + i * 98, 122, i == 0 ? 95 : 96, 16, label, ItemStack.EMPTY, s.tr("gui.begleiter.orders.tab." + tabs[i] + ".tooltip"),
                    () -> tab == index, () -> { tab = index; s.rebuildPage(); });
        }
        if (tab == 0) {
            s.commandButton(108, 142, 95, 19, "gui.begleiter.orders.obtain", new ItemStack(Items.CHEST_MINECART),
                    () -> CompanionMenuCommands.obtain(s.selectedItem(), s.selectedAmount()));
            s.commandButton(206, 142, 96, 19, "gui.begleiter.orders.craft", new ItemStack(Items.CRAFTING_TABLE),
                    () -> CompanionMenuCommands.craft(s.selectedItem(), s.selectedAmount()));
            s.commandButton(108, 163, 95, 19, "gui.begleiter.orders.materials", new ItemStack(Items.BUNDLE),
                    () -> CompanionMenuCommands.materials(s.selectedItem(), s.selectedAmount()));
            s.commandButton(206, 163, 96, 19, "gui.begleiter.orders.store", new ItemStack(Items.BARREL),
                    () -> CompanionMenuCommands.store(s.selectedItem(), s.selectedAmount(), false));
            s.commandButton(108, 184, 95, 19, "gui.begleiter.orders.urgent", new ItemStack(Items.BELL),
                    () -> CompanionMenuCommands.store(s.selectedItem(), s.selectedAmount(), true));
            var cancel = CompanionActions.get(6);
            s.button(206, 184, 96, 19, s.tr("gui.begleiter.orders.cancel"), new ItemStack(Items.BARRIER),
                    s.tr("gui.begleiter.orders.cancel.tooltip"), () -> false,
                    () -> s.send(CompanionMenu.ACTION_BUTTON + cancel.button()));
        } else {
            s.commandButton(108, 142, 95, 19, "gui.begleiter.stock.keep", new ItemStack(Items.BARREL),
                    () -> CompanionMenuCommands.keepStock(s.selectedItem(), s.selectedAmount()));
            s.commandButton(206, 142, 96, 19, "gui.begleiter.stock.remove", new ItemStack(Items.BARRIER),
                    () -> CompanionMenuCommands.clearStock(s.selectedItem()));
            s.commandButton(108, 163, 95, 19, "gui.begleiter.stock.gather", new ItemStack(Items.IRON_AXE),
                    () -> CompanionMenuCommands.stockGather(s.selectedItem(), true));
            s.commandButton(206, 163, 96, 19, "gui.begleiter.stock.local", new ItemStack(Items.CHEST),
                    () -> CompanionMenuCommands.stockGather(s.selectedItem(), false));
            s.commandButton(108, 184, 95, 19, "gui.begleiter.stock.list", new ItemStack(Items.WRITABLE_BOOK),
                    () -> CompanionMenuCommands.STOCK_LIST);
            s.commandButton(206, 184, 96, 19, "gui.begleiter.stock.clear", new ItemStack(Items.LAVA_BUCKET),
                    () -> CompanionMenuCommands.STOCK_CLEAR);
        }
    }

    private net.minecraft.client.gui.components.EditBox amountBox;

    private void setAmount(CompanionScreen s, int amount) {
        s.selectAmount(amount);
        if (amountBox != null) amountBox.setValue(String.valueOf(s.selectedAmount()));
    }

    /** Bis 16 in Einerschritten, darüber in 16er-Schritten (genaue Zahlen direkt ins Feld tippen). */
    private static int step(int n, boolean up) {
        return up ? (n >= 16 ? 16 : 1) : (n > 16 ? 16 : 1);
    }

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        int left = s.panelX(), top = s.panelY();
        ControlTheme.round(g, left + GRID_X - 2, top + GRID_Y - 2, COLS * CELL + 3, ROWS * CELL + 3, ControlTheme.CARD, ControlTheme.LINE);
        if (results.isEmpty()) s.text(g, s.tr("gui.begleiter.orders.none"), GRID_X + 4, GRID_Y + 15, 180, ControlTheme.MUTED);
        for (int i = 0; i < COLS * ROWS; i++) {
            int index = scroll * COLS + i;
            if (index >= results.size()) break;
            Item item = results.get(index);
            int x = GRID_X + i % COLS * CELL, y = GRID_Y + i / COLS * CELL;
            boolean hover = mx >= left + x && mx < left + x + CELL - 1 && my >= top + y && my < top + y + CELL - 1;
            boolean chosen = item == s.selectedItem();
            if (chosen || hover) ControlTheme.round(g, left + x, top + y, CELL - 1, CELL - 1,
                    chosen ? 0xFF1E4A42 : ControlTheme.HOVER, chosen ? ControlTheme.ACCENT : 0);
            var stack = new ItemStack(item);
            g.item(stack, left + x + 2, top + y + 2);
            if (hover) g.setTooltipForNextFrame(s.uiFont(), stack, mx, my);
        }
        int pages = (results.size() + COLS - 1) / COLS;
        if (pages > ROWS) {
            int track = ROWS * CELL - 2, thumb = Math.max(6, track * ROWS / pages);
            int at = GRID_Y + (track - thumb) * scroll / Math.max(1, pages - ROWS);
            g.fill(left + 299, top + GRID_Y, left + 300, top + GRID_Y + track, ControlTheme.LINE);
            g.fill(left + 299, top + at, left + 300, top + at + thumb, ControlTheme.ACCENT);
        }
        var chosen = new ItemStack(s.selectedItem());
        s.item(g, chosen, 110, 102, mx, my);
        s.text(g, chosen.getHoverName().getString(), 131, 106, 56, ControlTheme.TEXT);
        s.feedback(g, 210);
    }

    @Override public boolean mouseClicked(CompanionScreen s, double x, double y, int button) {
        if (x < GRID_X || x >= GRID_X + COLS * CELL || y < GRID_Y || y >= GRID_Y + ROWS * CELL) return false;
        int index = scroll * COLS + (int) ((y - GRID_Y) / CELL) * COLS + (int) ((x - GRID_X) / CELL);
        if (index < results.size()) s.selectItem(results.get(index));
        return true;
    }

    @Override public boolean mouseScrolled(CompanionScreen s, double x, double y, double amount) {
        if (x < GRID_X || x >= GRID_X + COLS * CELL + 4 || y < GRID_Y || y >= GRID_Y + ROWS * CELL) return false;
        int pages = (results.size() + COLS - 1) / COLS;
        scroll = Math.clamp(scroll + (amount < 0 ? 1 : -1), 0, Math.max(0, pages - ROWS));
        return true;
    }

    /** Sucht im angezeigten Namen (Spielsprache) und in der ID; leere Suche zeigt häufige Items. */
    static List<Item> search(String raw) {
        String query = raw.strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return COMMON;
        List<Item> exact = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (name.equals(query) || id.equals(query) || id.endsWith(":" + query)) exact.add(item);
            else if (name.startsWith(query) || id.contains(":" + query)) starts.add(item);
            else if (name.contains(query) || id.contains(query)) contains.add(item);
            if (exact.size() + starts.size() + contains.size() >= 270) break;
        }
        exact.addAll(starts);
        exact.addAll(contains);
        return List.copyOf(exact);
    }
}
