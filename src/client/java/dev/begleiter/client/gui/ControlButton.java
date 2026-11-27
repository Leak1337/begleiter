package dev.begleiter.client.gui;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Einheitliche Maus-, Fokus- und Tastaturbedienung über Minecraft-Buttons. */
public final class ControlButton extends Button {
    private final ItemStack icon;
    private final BooleanSupplier selected;
    public ControlButton(int x, int y, int width, String label, ItemStack icon,
            String tooltip, BooleanSupplier selected, Runnable action) {
        this(x, y, width, 20, label, icon, tooltip, selected, action);
    }
    public ControlButton(int x, int y, int width, int height, String label, ItemStack icon,
            String tooltip, BooleanSupplier selected, Runnable action) {
        super(x, y, width, height, Component.literal(label), b -> action.run(), DEFAULT_NARRATION);
        this.icon = icon;
        this.selected = selected;
        if (!tooltip.isEmpty()) setTooltip(Tooltip.create(Component.literal(tooltip)));
    }
    private String label(boolean marked) {
        return getMessage().getString();
    }

    private int indent() {
        return icon.isEmpty() ? 3 : getMessage().getString().isEmpty() ? (getWidth() - 16) / 2 : 24;
    }

    private int available() {
        return Math.max(0, getWidth() - indent() - 3);
    }

    /** true, wenn die Beschriftung im aktuellen Zustand ohne Lauftext passt. */
    public boolean labelFits() {
        return Minecraft.getInstance().font.width(label(selected.getAsBoolean())) <= available();
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        boolean marked = selected.getAsBoolean();
        boolean hot = active && isHoveredOrFocused();
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int top = !active ? 0xFF161F28 : hot ? 0xFF2F4659 : marked ? 0xFF1E3A3A : 0xFF22313F;
        int bottom = !active ? 0xFF131B23 : hot ? 0xFF253949 : marked ? 0xFF183030 : ControlTheme.PANEL;
        ControlTheme.round(g, x, y, w, h, 0, marked || hot ? ControlTheme.ACCENT : ControlTheme.LINE);
        ControlTheme.gradient(g, x + 1, y + 1, w - 2, h - 2, top, bottom);
        if (marked) g.fill(x + 1, y + 2, x + 3, y + h - 2, ControlTheme.ACCENT);
        int indent = indent();
        if (!icon.isEmpty()) g.item(icon, x + (getMessage().getString().isEmpty() ? indent : 4), y + (h - 16) / 2);
        var font = Minecraft.getInstance().font;
        String label = label(marked);
        if (label.isEmpty()) return;
        int available = available(), left = x + indent;
        int textY = y + (h - 8) / 2;
        int color = active ? (marked ? ControlTheme.ACCENT : ControlTheme.TEXT) : ControlTheme.MUTED;
        int overflow = font.width(label) - available;
        if (overflow <= 0) {
            // Ohne Symbol mittig, mit Symbol linksbündig neben dem Symbol
            int at = icon.isEmpty() ? x + (w - font.width(label)) / 2 : left;
            g.text(font, label, at, textY, color, false);
            return;
        }
        // Zu lang: wie bei Minecraft-Knöpfen langsam hin und her laufen statt abschneiden (alle Sprachen vollständig lesbar)
        double seconds = System.nanoTime() / 1.0e9;
        double period = Math.max(overflow * 0.5, 3.0);
        double phase = Math.sin(Math.PI / 2 * Math.cos(Math.PI * 2 * seconds / period)) / 2 + 0.5;
        g.enableScissor(left, y + 1, left + available, y + h - 1);
        g.text(font, label, left - (int) Math.round(phase * overflow), textY, color, false);
        g.disableScissor();
    }
    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        var message = super.createNarrationMessage();
        return selected.getAsBoolean() ? message.append(" · ").append(Component.translatable("gui.begleiter.selected")) : message;
    }
}
