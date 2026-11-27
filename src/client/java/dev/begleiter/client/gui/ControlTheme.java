package dev.begleiter.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Farben und kleine Zeichenhilfen des Control Centers (abgerundete Flächen, Karten, Verläufe). */
public final class ControlTheme {
    public static final int BACKGROUND = 0xF4111920, PANEL = 0xFF1B2733, LINE = 0xFF2E4254;
    public static final int TEXT = 0xFFEAF1F5, MUTED = 0xFF93A7B8, ACCENT = 0xFF6CE0BD;
    public static final int CARD = 0xFF16212B, HOVER = 0xFF263A4C, ACCENT_DARK = 0xFF2D7F6B;
    public static final int WARN = 0xFFF0C674, DANGER = 0xFFE87979, HEADER_TOP = 0xFF1F3242, HEADER_BOTTOM = 0xFF17242F;
    private ControlTheme() {}

    /** Fläche mit abgeschnittenen Ecken (1 px), optional mit Rand in derselben Form. */
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        if (w < 3 || h < 3) { g.fill(x, y, x + w, y + h, fill); return; }
        if (border != 0) {
            g.fill(x + 1, y, x + w - 1, y + h, border);
            g.fill(x, y + 1, x + w, y + h - 1, border);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        } else {
            g.fill(x + 1, y, x + w - 1, y + h, fill);
            g.fill(x, y + 1, x + w, y + h - 1, fill);
        }
    }

    /** Senkrechter Verlauf aus schmalen Bändern (ohne eigene Shader, sieht in jeder GUI-Skalierung gleich aus). */
    public static void gradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
        int bands = Math.max(1, Math.min(h, 12));
        for (int i = 0; i < bands; i++) {
            int y0 = y + h * i / bands, y1 = y + h * (i + 1) / bands;
            g.fill(x, y0, x + w, y1, mix(top, bottom, bands == 1 ? 0f : i / (float) (bands - 1)));
        }
    }

    public static int mix(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            r |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return r;
    }
}
