package dev.begleiter.client.gui;

import dev.begleiter.CompanionWaypointMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class WaypointMapScreen extends Screen {
    private final CompanionWaypointMap.Open data;
    private double scale;
    private double panX, panY;
    private int selected = -1;

    public WaypointMapScreen(CompanionWaypointMap.Open data) {
        super(Component.translatable("gui.begleiter.page.waypoints"));
        this.data = data;
    }

    private int left() { return 18; }
    private int right() { return width - 18; }
    private int top() { return 44; }
    private int bottom() { return height - 44; }
    private double centerX() { return width / 2.0 + panX; }
    private double centerY() { return height / 2.0 + panY; }
    private int x(CompanionWaypointMap.Point p) { return (int) Math.round(centerX() + p.x() * scale); }
    private int y(CompanionWaypointMap.Point p) { return (int) Math.round(centerY() + p.z() * scale); }
    private boolean inside(double x, double y) { return x >= left() && x < right() && y >= top() && y < bottom(); }

    @Override protected void init() {
        if (scale != 0) return;
        int range = data.points().stream().mapToInt(p -> Math.max(Math.abs(p.x()), Math.abs(p.z()))).max().orElse(32);
        scale = Math.clamp((Math.min(width, height) / 2.0 - 48) / Math.max(16, range), 0.000001, 4);
    }

    private int marker(double mx, double my) {
        int nearest = -1;
        double best = 36;
        for (int i = 0; i < data.points().size(); i++) {
            var point = data.points().get(i);
            double dx = x(point) - mx, dz = y(point) - my;
            double distance = dx * dx + dz * dz;
            if (distance < best) { best = distance; nearest = i; }
        }
        return nearest;
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (e.button() == 0 && inside(e.x(), e.y())) {
            selected = marker(e.x(), e.y());
            if (twice && selected >= 0 && ClientPlayNetworking.canSend(CompanionWaypointMap.Select.TYPE)) {
                ClientPlayNetworking.send(new CompanionWaypointMap.Select(data.token(), selected));
                onClose();
            }
            return true;
        }
        return super.mouseClicked(e, twice);
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (e.button() == 0 && inside(e.x(), e.y())) { panX += dx; panY += dy; return true; }
        return super.mouseDragged(e, dx, dy);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inside(x, y) || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        double next = Math.clamp(scale * Math.pow(1.35, vertical), 0.000001, 12);
        panX += (x - centerX()) * (1 - next / scale);
        panY += (y - centerY()) * (1 - next / scale);
        scale = next;
        return true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, ControlTheme.BACKGROUND);
        g.fill(left(), top(), right(), bottom(), ControlTheme.PANEL);
        g.outline(left(), top(), right() - left(), bottom() - top(), ControlTheme.LINE);
        g.text(font, title, 18, 13, ControlTheme.TEXT, false);
        g.text(font, data.dimension(), 18, 27, ControlTheme.MUTED, false);
        g.text(font, "N", width / 2 - 3, top() + 6, ControlTheme.MUTED, false);
        int cx = (int) Math.round(centerX()), cy = (int) Math.round(centerY());
        if (cx >= left() && cx < right()) g.fill(cx, top() + 1, cx + 1, bottom(), ControlTheme.LINE);
        if (cy >= top() && cy < bottom()) g.fill(left() + 1, cy, right(), cy + 1, ControlTheme.LINE);
        if (inside(cx, cy)) g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF79BFFF);
        int hovered = inside(mx, my) ? marker(mx, my) : -1;
        var labels = new java.util.ArrayList<net.minecraft.client.gui.navigation.ScreenRectangle>();
        for (int i = 0; i < data.points().size(); i++) {
            var point = data.points().get(i);
            int x = x(point), y = y(point);
            if (!inside(x, y)) continue;
            int color = i == hovered || i == selected ? 0xFFFFDA8C : ControlTheme.ACCENT;
            g.fill(x - 2, y - 2, x + 3, y + 3, color);
            int available = Math.min(120, right() - x - 8);
            if (available < 12) continue;
            String name = font.plainSubstrByWidth(point.name(), available);
            var bounds = new net.minecraft.client.gui.navigation.ScreenRectangle(x + 6, y - 5, font.width(name) + 3, 11);
            if (y - 5 >= top() && y + 6 <= bottom() && labels.stream().noneMatch(bounds::overlaps)) {
                labels.add(bounds);
                g.text(font, name, x + 6, y - 4, color, true);
            }
        }
        int focus = hovered >= 0 ? hovered : selected;
        if (focus >= 0) {
            var p = data.points().get(focus);
            var line = p.name() + "  " + data.anchor().offset(p.x(), p.y(), p.z()).toShortString();
            g.text(font, font.plainSubstrByWidth(line, width - 36), 18, bottom() + 8, ControlTheme.TEXT, false);
        }
        var key = data.points().isEmpty() ? "gui.begleiter.waypoint_map.empty" : "gui.begleiter.waypoint_map.controls";
        g.text(font, Component.translatable(key), 18, height - 17, ControlTheme.MUTED, false);
        super.extractRenderState(g, mx, my, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
