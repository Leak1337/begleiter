package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

public final class CombatPage implements ControlPage {
    public Page page() { return Page.COMBAT; }
    public Item icon() { return Items.IRON_SWORD; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.heading(g, s.tr("gui.begleiter.combat.heading"), 108, 44, 194);
        s.text(g, s.tr("gui.begleiter.combat.target"), 110, 67, 188, ControlTheme.MUTED);
        s.paragraph(g, s.data().target(), 110, 82, 188, 2, ControlTheme.ACCENT);
        var hands = new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND};
        String[] labels = {"mainhand", "offhand"};
        for (int i = 0; i < 2; i++) {
            String label = s.tr("gui.begleiter.equipment.slot." + labels[i]);
            s.item(g, s.equipment(hands[i]), 112 + i * 97, 108, mx, my, s.tr("gui.begleiter.equipment.empty", label));
            s.text(g, label, 134 + i * 97, 112, 70, ControlTheme.TEXT);
        }
        s.paragraph(g, s.tr("gui.begleiter.combat.hint"), 110, 154, 188, 2, ControlTheme.MUTED);
    }
    public void init(CompanionScreen s) {
        s.commandButton(108, 131, 194, 18, "gui.begleiter.talk.protect", new ItemStack(Items.SHIELD),
                () -> dev.begleiter.CompanionMenuCommands.PROTECT);
        for (int i = 0; i < 3; i++)
            s.actionButton(108 + i * 65, 180, 64, dev.begleiter.CompanionActions.get(33 + i), false);
    }
}
