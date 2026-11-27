package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenuCommands;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import dev.begleiter.client.gui.ControlTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Hintergrundarbeiten ({@code @ work ...}) mit Knöpfen: Job wählen, verwalten, Priorität, Limit und Budgets. */
public final class WorkPage implements ControlPage {
    private static final Item[] ICONS = {Items.WHEAT, Items.HAY_BLOCK, Items.CHEST, Items.BARREL, Items.COMPASS,
            Items.BRICKS, Items.EMERALD};
    private static final int[] LIMITS = {1, 2, 4, 8, 16, 32};
    private int job;
    private final int[] priority = {3, 3, 3, 3, 3, 3, 3};
    private final int[] limit = {8, 4, 1, 1, 1, 16, 4};

    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.WORK; }
    @Override public Item icon() { return Items.IRON_HOE; }
    @Override public boolean quickBar() { return false; }

    private String name() { return CompanionMenuCommands.WORK_JOBS.get(job); }

    @Override public void init(CompanionScreen s) {
        for (int i = 0; i < ICONS.length; i++) {
            int index = i;
            String jobName = CompanionMenuCommands.WORK_JOBS.get(i);
            s.button(108 + i * 28, 40, 26, 20, "", new ItemStack(ICONS[i]),
                    s.tr("gui.begleiter.work.job." + jobName) + "\n" + s.tr("gui.begleiter.work.job." + jobName + ".tooltip"),
                    () -> job == index, () -> { job = index; s.rebuildPage(); });
        }
        String[] actions = {"add", "pause", "resume", "remove"};
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            s.commandButton(108 + i * 49, 75, 47, 18, "gui.begleiter.work." + action, ItemStack.EMPTY,
                    () -> CompanionMenuCommands.work(name(), action));
        }
        s.button(108, 96, 95, 18, s.tr("gui.begleiter.work.priority", priority[job]), ItemStack.EMPTY,
                s.tr("gui.begleiter.work.priority.tooltip"), () -> false, () -> {
                    priority[job] = priority[job] % 5 + 1;
                    s.sendCommand(CompanionMenuCommands.workValue(name(), "priority", priority[job]));
                    s.rebuildPage();
                });
        s.button(206, 96, 96, 18, s.tr("gui.begleiter.work.limit", limit[job]), ItemStack.EMPTY,
                s.tr("gui.begleiter.work.limit.tooltip"), () -> false, () -> {
                    limit[job] = nextLimit(limit[job], "animals".equals(name()));
                    s.sendCommand(CompanionMenuCommands.workValue(name(), "limit", limit[job]));
                    s.rebuildPage();
                });
        s.commandButton(108, 117, 63, 18, "gui.begleiter.work.history", ItemStack.EMPTY,
                () -> CompanionMenuCommands.work(name(), "history"));
        s.commandButton(173, 117, 63, 18, "gui.begleiter.work.why", ItemStack.EMPTY,
                () -> CompanionMenuCommands.work(name(), "why"));
        s.commandButton(238, 117, 64, 18, "gui.begleiter.work.budgets", ItemStack.EMPTY,
                () -> CompanionMenuCommands.work(name(), "budget"));
        String chosen = new ItemStack(s.selectedItem()).getHoverName().getString();
        s.button(108, 138, 95, 18, s.tr("gui.begleiter.work.budget.set", s.selectedAmount()), new ItemStack(s.selectedItem()),
                s.tr("gui.begleiter.work.budget.set.tooltip", s.selectedAmount(), chosen), () -> false,
                () -> s.sendCommand(CompanionMenuCommands.workBudget(name(), s.selectedItem(), s.selectedAmount())));
        s.button(206, 138, 96, 18, s.tr("gui.begleiter.work.budget.remove"), new ItemStack(s.selectedItem()),
                s.tr("gui.begleiter.work.budget.remove.tooltip", chosen), () -> false,
                () -> s.sendCommand(CompanionMenuCommands.workBudgetRemove(name(), s.selectedItem())));
        s.commandButton(108, 165, 63, 18, "gui.begleiter.work.on", ItemStack.EMPTY, () -> CompanionMenuCommands.WORK_ON);
        s.commandButton(173, 165, 63, 18, "gui.begleiter.work.off", ItemStack.EMPTY, () -> CompanionMenuCommands.WORK_OFF);
        s.commandButton(238, 165, 64, 18, "gui.begleiter.work.list", ItemStack.EMPTY, () -> CompanionMenuCommands.WORK_LIST);
        s.commandButton(108, 186, 95, 18, "gui.begleiter.work.wood_scoped", new ItemStack(Items.OAK_SAPLING),
                () -> CompanionMenuCommands.WOOD_SCOPED);
        s.commandButton(206, 186, 96, 18, "gui.begleiter.work.wood_free", new ItemStack(Items.OAK_LOG),
                () -> CompanionMenuCommands.WOOD_FREE);
    }

    private static int nextLimit(int current, boolean animals) {
        for (int value : LIMITS) if (value > current && (!animals || value >= 2)) return value;
        return animals ? 2 : 1;
    }

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.text(g, s.tr("gui.begleiter.work.job." + name()) + " · " + s.tr("gui.begleiter.work.job." + name() + ".tooltip"),
                110, 64, 190, ControlTheme.TEXT);
        s.feedback(g, 210);
    }
}
