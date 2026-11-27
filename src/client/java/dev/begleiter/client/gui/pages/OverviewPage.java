package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu.Page;
import dev.begleiter.client.gui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class OverviewPage implements ControlPage {
    public Page page() { return Page.MAIN; }
    public Item icon() { return Items.COMPASS; }
    public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        var d = s.data();
        s.card(g, 107, 40, 196, 42, s.tr("gui.begleiter.overview.task"));
        s.paragraph(g, d.activity(), 112, 55, 186, 2, ControlTheme.ACCENT);
        s.card(g, 107, 85, 97, 44, null);
        s.text(g, s.tr("gui.begleiter.overview.slots", d.usedSlots()), 112, 90, 88, ControlTheme.TEXT);
        drawBar(s, g, 112, 103, 88, d.usedSlots(), 36, ControlTheme.ACCENT);
        s.text(g, s.tr("gui.begleiter.overview.free", Math.max(0, 36 - d.usedSlots())), 112, 112, 88, ControlTheme.MUTED);
        s.card(g, 206, 85, 97, 44, null);
        s.text(g, s.tr("gui.begleiter.status.hunger", d.hunger()), 211, 90, 88, ControlTheme.TEXT);
        drawBar(s, g, 211, 103, 88, d.hunger(), 20, ControlTheme.WARN);
        s.text(g, s.tr("gui.begleiter.overview.queue.short", d.queued()), 211, 112, 88, ControlTheme.MUTED);
        s.card(g, 107, 132, 196, 42, s.tr("gui.begleiter.page.equipment"));
        var slots = new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND};
        String[] labels = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
        for (int i = 0; i < slots.length; i++) s.item(g, s.equipment(slots[i]), 114 + i * 31, 150, mx, my,
                s.tr("gui.begleiter.equipment.empty", s.tr("gui.begleiter.equipment.slot." + labels[i])));
        if (d.hasProblem()) s.paragraph(g, d.problemText().getString(), 112, 182, 186, 2, 0xFFFFBA79);
        else s.feedback(g, 184);
    }
    private static void drawBar(CompanionScreen s, GuiGraphicsExtractor g, int x, int y, int width, int value, int max, int color) {
        int left = s.panelX() + x;
        int top = s.panelY() + y;
        int filled = Math.round(width * Math.clamp((float) value / max, 0f, 1f));
        g.fill(left, top, left + width, top + 4, 0xFF0D141A);
        if (filled > 0) g.fill(left, top, left + filled, top + 4, color);
    }
}
