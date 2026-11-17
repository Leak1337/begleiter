package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class EquipmentPage implements ControlPage {
    public Page page() { return Page.EQUIPMENT; }
    public Item icon() { return Items.IRON_CHESTPLATE; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        s.text(g, s.tr("gui.begleiter.equipment.heading"), 110, 45, 188, ControlTheme.MUTED);
        String[] labels = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
        for (int i = 0; i < slots.length; i++) {
            int x = 112 + i % 2 * 96, y = 69 + i / 2 * 37;
            String label = s.tr("gui.begleiter.equipment.slot." + labels[i]);
            s.item(g, s.equipment(slots[i]), x, y, mx, my, s.tr("gui.begleiter.equipment.empty", label));
            s.text(g, label, x + 24, y + 4, 68, ControlTheme.TEXT);
        }
        s.paragraph(g, s.tr("gui.begleiter.equipment.hint"),
                110, 181, 188, 2, ControlTheme.MUTED);
    }
}
