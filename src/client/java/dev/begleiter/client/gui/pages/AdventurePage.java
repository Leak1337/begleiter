package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenuCommands;
import dev.begleiter.CompanionTerraform;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import dev.begleiter.client.gui.ControlTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Selbstständiges Survival-Ziel, Angeln und Geländearbeiten ohne Chatbefehl. */
public final class AdventurePage implements ControlPage {
    private static final int[] SIZES = {5, 7, 9, 11, 15, 21, 25, 33};
    private static final CompanionTerraform.Mode[] MODES = CompanionTerraform.Mode.values();
    private int fish = 5;
    private int mode;
    private int size = 3;

    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.ADVENTURE; }
    @Override public Item icon() { return Items.ENDER_EYE; }
    @Override public boolean quickBar() { return false; }

    @Override public void init(CompanionScreen s) {
        s.commandButton(108, 51, 95, 19, "gui.begleiter.adventure.iron", new ItemStack(Items.IRON_CHESTPLATE),
                () -> CompanionMenuCommands.SURVIVAL_IRON);
        s.commandButton(206, 51, 96, 19, "gui.begleiter.adventure.dragon", new ItemStack(Items.DRAGON_HEAD),
                () -> CompanionMenuCommands.SURVIVAL_DRAGON);
        String[] keys = {"status", "pause", "resume", "clear"};
        String[] commands = {CompanionMenuCommands.SURVIVAL_STATUS, CompanionMenuCommands.SURVIVAL_PAUSE,
                CompanionMenuCommands.SURVIVAL_RESUME, CompanionMenuCommands.SURVIVAL_CLEAR};
        for (int i = 0; i < keys.length; i++) {
            String command = commands[i];
            s.commandButton(108 + i * 49, 72, 47, 17, "gui.begleiter.adventure." + keys[i], ItemStack.EMPTY, () -> command);
        }
        s.button(108, 108, 14, 17, "-", ItemStack.EMPTY, s.tr("gui.begleiter.adventure.fish.count"), () -> false,
                () -> { fish = Math.max(1, fish - 1); s.rebuildPage(); });
        s.button(146, 108, 14, 17, "+", ItemStack.EMPTY, s.tr("gui.begleiter.adventure.fish.count"), () -> false,
                () -> { fish = Math.min(64, fish + 1); s.rebuildPage(); });
        s.button(163, 108, 70, 17, s.tr("gui.begleiter.adventure.fish.catch", fish), new ItemStack(Items.COD),
                s.tr("gui.begleiter.adventure.fish.catch.tooltip", fish), () -> false,
                () -> s.sendCommand(CompanionMenuCommands.catchFish(fish)));
        s.commandButton(236, 108, 66, 17, "gui.begleiter.adventure.fish.go", ItemStack.EMPTY,
                () -> CompanionMenuCommands.GO_FISHING);
        var current = MODES[mode];
        String modeKey = "gui.begleiter.adventure.terrain." + current.name().toLowerCase(java.util.Locale.ROOT);
        s.button(108, 145, 95, 17, s.tr(modeKey) + " ›", ItemStack.EMPTY, s.tr(modeKey + ".tooltip"), () -> false,
                () -> { mode = (mode + 1) % MODES.length; s.rebuildPage(); });
        s.button(206, 145, 46, 17, SIZES[size] + "×" + SIZES[size], ItemStack.EMPTY, s.tr("gui.begleiter.adventure.terrain.size"),
                () -> false, () -> { size = (size + 1) % SIZES.length; s.rebuildPage(); });
        s.button(255, 145, 47, 17, s.tr("gui.begleiter.adventure.terrain.start"), ItemStack.EMPTY,
                s.tr("gui.begleiter.adventure.terrain.start.tooltip"), () -> false,
                () -> s.sendCommand(CompanionMenuCommands.terrain(MODES[mode], SIZES[size])));
        s.commandButton(108, 166, 95, 17, "gui.begleiter.adventure.door", new ItemStack(Items.OAK_DOOR),
                () -> CompanionMenuCommands.DOOR);
        s.commandButton(206, 166, 96, 17, "gui.begleiter.adventure.project", new ItemStack(Items.MAP),
                () -> CompanionMenuCommands.PROJECT_PREVIEW);
    }

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.heading(g, s.tr("gui.begleiter.adventure.survival"), 108, 41, 194);
        s.heading(g, s.tr("gui.begleiter.adventure.fishing"), 108, 97, 194);
        String count = String.valueOf(fish);
        s.text(g, count, 134 - s.uiFont().width(count) / 2, 113, 20, ControlTheme.ACCENT);
        s.heading(g, s.tr("gui.begleiter.adventure.terrain"), 108, 134, 194);
        s.feedback(g, 192);
    }
}
