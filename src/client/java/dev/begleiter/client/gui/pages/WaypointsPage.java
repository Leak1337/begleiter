package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionMenuCommands;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Wegpunktkarte plus Speichern, Hingehen und Vergessen über einen Namen. */
public final class WaypointsPage implements ControlPage {
    private String name = "";
    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.WAYPOINTS; }
    @Override public Item icon() { return Items.FILLED_MAP; }
    @Override public void init(CompanionScreen s) {
        String label = s.tr("gui.begleiter.waypoint_map.open");
        s.button(108, 51, 194, 20, label, new ItemStack(icon()), s.tr("gui.begleiter.waypoints.map.tooltip"), () -> false,
                () -> s.send(CompanionMenu.WAYPOINT_MAP_OPEN));
        s.editBox(108, 94, 194, 18, s.tr("gui.begleiter.waypoints.name"), name, 32, value -> name = value,
                () -> s.sendCommand(save()));
        s.button(108, 115, 63, 18, s.tr("gui.begleiter.waypoints.save"), ItemStack.EMPTY, s.tr("gui.begleiter.waypoints.save.tooltip"),
                () -> false, () -> s.sendCommand(save()));
        s.button(173, 115, 63, 18, s.tr("gui.begleiter.waypoints.go"), ItemStack.EMPTY, s.tr("gui.begleiter.waypoints.go.tooltip"),
                () -> false, () -> s.sendCommand(named(CompanionMenuCommands::waypointGo)));
        s.button(238, 115, 64, 18, s.tr("gui.begleiter.waypoints.forget"), ItemStack.EMPTY, s.tr("gui.begleiter.waypoints.forget.tooltip"),
                () -> false, () -> s.sendCommand(named(CompanionMenuCommands::waypointForget)));
        s.actionButton(108, 145, 95, CompanionActions.get(30), false);
        s.actionButton(206, 145, 96, CompanionActions.get(31), false);
    }
    private String save() { return named(CompanionMenuCommands::waypointSave); }
    private String named(java.util.function.UnaryOperator<String> command) {
        return CompanionMenuCommands.waypointName(name).isEmpty() ? null : command.apply(name);
    }
    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.heading(g, s.tr("gui.begleiter.page.waypoints"), 108, 41, 194);
        s.heading(g, s.tr("gui.begleiter.waypoints.named"), 108, 82, 194);
        s.feedback(g, 177);
    }
}
