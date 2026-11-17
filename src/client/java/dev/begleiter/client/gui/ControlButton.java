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
        super(x, y, width, 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION);
        this.icon = icon;
        this.selected = selected;
        setTooltip(Tooltip.create(Component.literal(tooltip)));
    }
    private String label(boolean marked) {
        return (marked && icon.isEmpty() ? "› " : "") + getMessage().getString();
    }

    private int available() {
        return getWidth() - (icon.isEmpty() ? 6 : 22) - 4;
    }

    /** true, wenn die Beschriftung im aktuellen Zustand ohne Lauftext passt. */
    public boolean labelFits() {
        return Minecraft.getInstance().font.width(label(selected.getAsBoolean())) <= available();
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        boolean marked = selected.getAsBoolean();
        int x = getX(), y = getY();
        g.fill(x, y, x + getWidth(), y + getHeight(), isHoveredOrFocused() ? 0xFF304455 : ControlTheme.PANEL);
        g.outline(x, y, getWidth(), getHeight(), marked || isHoveredOrFocused() ? ControlTheme.ACCENT : ControlTheme.LINE);
        if (marked) g.fill(x, y, x + 3, y + getHeight(), ControlTheme.ACCENT);
        int indent = icon.isEmpty() ? 6 : 22;
        if (!icon.isEmpty()) g.item(icon, x + 4, y + 2);
        var font = Minecraft.getInstance().font;
        String label = label(marked);
        int available = available(), left = x + indent;
        int color = active ? (marked ? ControlTheme.ACCENT : ControlTheme.TEXT) : ControlTheme.MUTED;
        int overflow = font.width(label) - available;
        if (overflow <= 0) {
            g.text(font, label, left, y + 6, color, false);
            return;
        }
        // Zu lang: wie bei Minecraft-Knöpfen langsam hin und her laufen statt abschneiden (alle Sprachen vollständig lesbar)
        double seconds = System.nanoTime() / 1.0e9;
        double period = Math.max(overflow * 0.5, 3.0);
        double phase = Math.sin(Math.PI / 2 * Math.cos(Math.PI * 2 * seconds / period)) / 2 + 0.5;
        g.enableScissor(left, y + 1, left + available, y + getHeight() - 1);
        g.text(font, label, left - (int) Math.round(phase * overflow), y + 6, color, false);
        g.disableScissor();
    }
    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        var message = super.createNarrationMessage();
        return selected.getAsBoolean() ? message.append(" · ").append(Component.translatable("gui.begleiter.selected")) : message;
    }
}
