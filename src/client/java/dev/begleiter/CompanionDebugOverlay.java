package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.begleiter.client.gui.BotDebugScreen;
import dev.begleiter.client.gui.GuiText;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebugClient;
import dev.begleiter.debug.BotDebugConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Debug-Anzeige der Begleiter: HUD oben links (Bot-Status, Wegfindung, Aufgaben) und Bot-ESP in der Welt (Boxen,
 * Linien, Namensschilder, Pfad). Liest ausschließlich die Server-Schnappschüsse aus {@link BotDebugClient}, funktioniert
 * also auch im Mehrspielermodus; Einstellungen in {@link BotDebugConfig}, Bedienung im {@link BotDebugScreen}.
 * Entfernen: diese Datei, das Paket dev.begleiter.debug und den Aufruf in {@link CompanionClient} löschen.
 */
public final class CompanionDebugOverlay {
    public static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, YELLOW = 0xFFFFE060, RED = 0xFFFF6060,
            GREEN = 0xFF55FF55, CYAN = 0xFF40E0FF, ORANGE = 0xFFFFA040, BLUE = 0xFF80A0FF, PURPLE = 0xFFD080FF,
            PINK = 0xFFFF80C0, PATH_DONE = 0xFF7080A0;
    private static final int BG = 0x90000000, CURRENT_BG = 0xC0208A6A;
    private static final int HUD_WIDTH = 250, HUD_TASKS = BotDebug.MAX_TASKS;

    /** Alles ausblenden (Tests mit Bildschirmfotos setzen das auf false). */
    public static boolean enabled = true;

    private CompanionDebugOverlay() { }

    public static void register() {
        LevelRenderEvents.END_EXTRACTION.register(CompanionDebugOverlay::drawWorld);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("begleiter", "debug_board"), CompanionDebugOverlay::drawHud);
    }

    public static int stateColor(int state) {
        return state == BotDebug.STATE_ERROR ? RED : state == BotDebug.STATE_PAUSED ? YELLOW : GREEN;
    }

    public static String stateText(int state) {
        return t(state == BotDebug.STATE_ERROR ? "debug.begleiter.state.error"
                : state == BotDebug.STATE_PAUSED ? "debug.begleiter.state.paused" : "debug.begleiter.state.active");
    }

    /** Fester HUD-Text in der Minecraft-Sprache; Serverwerte (Mission, Plan, Aufgaben) bleiben unverändert. */
    private static String t(String key, Object... args) { return GuiText.tr(key, args); }

    private static int fractionColor(float f) {
        return f > 0.6f ? GREEN : f > 0.3f ? YELLOW : RED;
    }

    /** Farbe einer Log-Meldung, 0 = uninteressant (Laufen, Weg planen, Stein abbauen ...). */
    public static int actionColor(String s) {
        return BotDebug.eventColor(s);
    }

    // ------------------------------------------------------------------ HUD

    private record Seg(String text, int color) {}

    /** Eine HUD-Zeile aus farbigen Teilen; bg != 0 legt einen farbigen Balken dahinter. */
    private record Row(int bg, List<Seg> segs) {
        static Row of(Seg... s) { return new Row(0, List.of(s)); }
        static Row bar(int bg, Seg... s) { return new Row(bg, List.of(s)); }
    }

    private static Seg s(String text, int color) { return new Seg(text, color); }

    private static List<Row> hudRows() {
        List<Row> rows = new ArrayList<>();
        if (BotDebugClient.denied()) {
            rows.add(Row.of(s(t("debug.begleiter.hud.denied"), GRAY)));
            return rows;
        }
        if (!BotDebugClient.hasData()) return rows; // Server ohne Mod oder noch keine Daten
        BotDebug.Entry e = BotDebugClient.selectedEntry();
        if (e == null) {
            rows.add(Row.of(s(t("debug.begleiter.hud.no_bot"), GRAY)));
            return rows;
        }
        BotDebug.Detail d = BotDebugClient.detail();
        Minecraft mc = Minecraft.getInstance();
        double dist = mc.player == null ? 0 : Math.sqrt(mc.player.distanceToSqr(e.x(), e.y(), e.z()));
        boolean sameDim = mc.level != null && mc.level.dimension().identifier().toString().equals(e.dim());
        rows.add(Row.of(s(e.name(), YELLOW), s("  [" + (d == null ? "?" : d.mode()) + "] ", WHITE),
                s(stateText(e.state()), stateColor(e.state())),
                s(sameDim ? String.format(Locale.ROOT, "  %.0f m", dist) : "  " + shortDim(e.dim()), WHITE)));
        rows.add(Row.of(s(t("debug.begleiter.ov.health") + " ", GRAY), s(Math.round(e.health()) + "/" + Math.round(e.maxHealth()), fractionColor(e.health() / Math.max(1f, e.maxHealth()))),
                s("  " + t("debug.begleiter.ov.hunger") + " ", GRAY), s(e.hunger() + "/20", fractionColor(e.hunger() / 20f)),
                s("  XYZ ", GRAY), s((int) Math.floor(e.x()) + " " + (int) Math.floor(e.y()) + " " + (int) Math.floor(e.z()), WHITE)));
        if (d == null) return rows;
        rows.add(Row.of(s(t("debug.begleiter.hud.carrying") + " ", GRAY), s(t("debug.begleiter.hud.diamond", d.diamond()), CYAN),
                s("  " + t("debug.begleiter.hud.iron", d.iron()), WHITE), s("  " + t("debug.begleiter.hud.coal", d.coal()), GRAY),
                s(d.mine().isEmpty() ? "" : "  " + t("debug.begleiter.hud.mine", d.mine()), GRAY)));
        rows.add(Row.of(s(t("debug.begleiter.hud.mission") + " ", GRAY), s(d.mission(), YELLOW)));
        rows.add(Row.of(s(t("debug.begleiter.hud.plan") + " ", GRAY), s(d.plan(), WHITE)));
        rows.add(Row.of(s(t("debug.begleiter.hud.now") + " ", GRAY), s(d.action(), GREEN), s("  " + d.actionSec() + "s", GRAY),
                s("  " + t("debug.begleiter.ov.uptime") + " " + clock(d.aliveSec()), GRAY)));
        if (!d.lastError().isEmpty() && d.errorAgeSec() < 30)
            rows.add(Row.of(s(t("debug.begleiter.hud.problem") + " ", GRAY), s(d.lastError(), RED)));
        if (d.navActive()) {
            rows.add(Row.of(s(t("debug.begleiter.hud.path") + " ", GRAY), s(d.pathLength() == 0 ? t("debug.begleiter.nav.searching")
                    : t("debug.begleiter.hud.step", d.pathIndex(), d.pathLength())
                    + (d.partial() ? " (" + t("debug.begleiter.nav.partial") + ")" : ""), CYAN),
                    s("  " + d.moveType(), WHITE), s(String.format(Locale.ROOT, "  %.1f m", d.stepLength()), GRAY)));
            rows.add(Row.of(s(t("debug.begleiter.nav.nodes") + " ", GRAY), s(String.valueOf(d.nodes()), WHITE),
                    s("  " + t("debug.begleiter.nav.compute") + " ", GRAY), s(String.format(Locale.ROOT, "%.1f ms", d.ms()), d.ms() > 20 ? ORANGE : WHITE),
                    s(d.failures() > 0 ? "  " + t("debug.begleiter.nav.failures") + " " + d.failures() : "", RED)));
        }
        rows.add(Row.of(s(t("debug.begleiter.hud.tasks") + " ", GRAY), s(String.valueOf(d.taskTotal()), WHITE)));
        for (int i = 0; i < Math.min(HUD_TASKS, d.taskLines().size()); i++) {
            var task = d.taskLines().get(i);
            String text = task.text().getString();
            rows.add(i == 0 ? Row.bar(CURRENT_BG, s("> " + text, WHITE)) : Row.bar(taskBg(task.kind()), s("  " + text, 0xFFDDDDDD)));
        }
        if (d.taskTotal() > HUD_TASKS) rows.add(Row.of(s("  … +" + (d.taskTotal() - HUD_TASKS), GRAY)));
        List<Row> recent = new ArrayList<>();
        for (var line : d.logLines()) {
            if (!line.recent()) continue;
            int color = BotDebug.eventColor(line.kind());
            recent.add(Row.of(s("  " + line.ageSec() + "s  ", GRAY),
                    s(line.text().getString() + (line.repetitions() == 1 ? "" : " x" + line.repetitions()), color != 0 ? color : WHITE)));
        }
        if (!recent.isEmpty()) {
            rows.add(Row.of(s(t("debug.begleiter.hud.recent"), WHITE)));
            rows.addAll(recent);
        }
        return rows;
    }

    /** Farbe je Aufgabenart aus festen Daten, unabhängig von der Sprache des Clients. */
    private static int taskBg(CompanionEntity.TaskType task) {
        int rgb = switch (task) {
            case GATHER, MINE, BREAK, TREE, COLLECT, TAKE -> 0x6A4A20;        // Abbauen/Sammeln: braun
            case MOVE_TO, TRAVEL, CLIMB, DESCEND, PILLAR -> 0x24508A;            // Laufen: blau
            case CRAFT, SMELT, STATION, SORT -> 0x5A3090;                         // Herstellen: lila
            case BUILD, HOUSE, PLACE, EXTEND -> 0x8A2E60;                         // Bauen: pink
            case HUNT, FIGHT, CRYSTALS -> 0x902828;                                 // Kampf: rot
            case WATER, POUR, PORTAL, STRONGHOLD -> 0x206A70;                     // Wasser/Portal: türkis
            case DELIVER, GIVE, FETCH -> 0x806A10;                                  // Bringen: gelb
            default -> 0x404040;
        };
        return 0x80000000 | rgb;
    }

    private static String shortDim(String dim) {
        return dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
    }

    public static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        BotDebugConfig cfg = BotDebugConfig.get();
        if (!enabled || !cfg.hud || mc.player == null || mc.level == null || mc.gui.screen() instanceof BotDebugScreen) return;
        List<Row> rows = hudRows();
        if (rows.isEmpty()) return;
        Font font = mc.font;
        int lineH = 9;
        List<String> texts = new ArrayList<>(rows.size());
        int w = 0;
        for (Row r : rows) {
            StringBuilder sb = new StringBuilder();
            for (Seg seg : r.segs()) sb.append(seg.text());
            texts.add(sb.toString());
            w = Math.max(w, Math.min(HUD_WIDTH, font.width(sb.toString())));
        }
        g.pose().pushMatrix();
        g.pose().translate(cfg.hudX, cfg.hudY);
        g.pose().scale(cfg.hudScale, cfg.hudScale);
        g.fill(-2, -2, w + 2, rows.size() * lineH + 1, BG);
        int y = 0;
        for (Row r : rows) {
            if (r.bg() != 0) g.fill(-2, y - 1, w + 2, y + lineH - 1, r.bg());
            int x = 0, left = w;
            for (Seg seg : r.segs()) {
                String t = fit(font, seg.text(), left);
                g.text(font, t, x, y, seg.color(), false);
                int tw = font.width(t);
                x += tw;
                left -= tw;
                if (left <= 0) break;
            }
            y += lineH;
        }
        g.pose().popMatrix();
    }

    /** Kürzt auf die verfügbare Breite und hängt "…" an. */
    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        int ellipsis = font.width("…");
        return maxWidth <= ellipsis ? "" : font.plainSubstrByWidth(text, maxWidth - ellipsis) + "…";
    }

    // ------------------------------------------------------------------ ESP in der Welt

    private static void drawWorld(LevelExtractionContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        BotDebugConfig cfg = BotDebugConfig.get();
        if (!enabled || mc.player == null || mc.level == null || !BotDebugClient.allowed()) return;
        boolean showPath = cfg.path;
        boolean boxes = cfg.esp != BotDebugConfig.Esp.OFF;
        if (!boxes && !cfg.lines && !cfg.nameTags && !showPath) return;
        float pt = ctx.deltaTracker().getGameTimeDeltaPartialTick(false);
        String dim = mc.level.dimension().identifier().toString();
        Vec3 cam = ctx.camera().position();
        var f = ctx.camera().forwardVector();
        var up = ctx.camera().upVector();
        // Start knapp vor der Kamera (sonst liegt der Anfang im Auge und die Linie ist kaum zu sehen)
        double drop = cfg.lineOrigin == BotDebugConfig.LineOrigin.BOTTOM ? 0.28 : 0;
        Vec3 from = cam.add(f.x() * 0.6 - up.x() * drop, f.y() * 0.6 - up.y() * drop, f.z() * 0.6 - up.z() * drop);
        java.util.UUID selected = BotDebugClient.selected();

        for (BotDebug.Entry e : BotDebugClient.bots()) {
            if (!e.dim().equals(dim)) continue;
            boolean sel = e.id().equals(selected);
            if (cfg.esp == BotDebugConfig.Esp.SELECTED && !sel) continue; // Linien und Schilder laufen auch bei ESP "Aus" (dann für alle Bots)
            AABB box = boxOf(mc, e, pt);
            if (box == null) continue;
            Vec3 center = box.getCenter();
            double dist = cam.distanceTo(center);
            if (dist > cfg.range) continue;
            int color = sel ? CYAN : stateColor(e.state());
            int fill = (sel ? 0x55 : 0x30) << 24 | (color & 0xFFFFFF);
            if (boxes) Gizmos.cuboid(box.inflate(sel ? 0.04 : 0.01), GizmoStyle.strokeAndFill(color, sel ? 3f : 1.5f, fill)).setAlwaysOnTop();
            if (cfg.lines) Gizmos.line(from, center, color, sel ? 3f : 2f).setAlwaysOnTop();
            if (cfg.nameTags) {
                StringBuilder tag = new StringBuilder(e.name());
                if (cfg.tagDistance) tag.append(String.format(Locale.ROOT, " · %.0f m", dist));
                if (cfg.tagStatus) tag.append(" · ").append(e.status().isEmpty() ? stateText(e.state()) : e.status());
                float k = (float) Math.clamp(dist / 10.0, 1.0, 6.0); // mit der Entfernung größer, damit lesbar
                var style = TextGizmo.Style.forColorAndCentered(color).withScale(TextGizmo.Style.DEFAULT_SCALE * k);
                Gizmos.billboardText(tag.toString(), new Vec3(center.x, box.maxY + 0.35 * k, center.z), style).setAlwaysOnTop();
            }
        }
        if (showPath) drawPath(mc, dim, selected);
    }

    /** Lebendes Entity (interpoliert) oder, außerhalb der Reichweite des Clients, die Position aus dem Schnappschuss. */
    private static AABB boxOf(Minecraft mc, BotDebug.Entry e, float pt) {
        var ent = mc.level.getEntity(e.entityId());
        if (ent instanceof CompanionEntity c) {
            if (c.isRemoved() || !c.isAlive()) return null; // despawnt: sofort keine Markierung mehr
            return c.getBoundingBox().move(c.getPosition(pt).subtract(c.position()));
        }
        return new AABB(e.x() - 0.3, e.y(), e.z() - 0.3, e.x() + 0.3, e.y() + 1.8, e.z() + 0.3);
    }

    private static final int PATH_TILES = 60;

    private static void drawPath(Minecraft mc, String dim, java.util.UUID selected) {
        BotDebug.Entry e = BotDebugClient.selectedEntry();
        BotDebug.Detail d = BotDebugClient.detail();
        if (e == null || d == null || !e.dim().equals(dim) || !e.id().equals(selected)) return;
        long[] path = d.path();
        if (path.length > 2) {
            int first = (int) path[0];
            Vec3 prev = pathPoint(path[1]);
            for (int i = 2; i < path.length; i++) {
                Vec3 next = pathPoint(path[i]);
                int idx = first + i - 2; // Index des Segments prev → next
                int color = idx < d.pathIndex() ? PATH_DONE : idx == d.pathIndex() ? YELLOW : CYAN;
                Gizmos.line(prev, next, color, idx == d.pathIndex() ? 9f : idx < d.pathIndex() ? 4f : 6f).setAlwaysOnTop();
                // Kachel auf dem Boden der Stufe: nur kurz vor/hinter dem Bot, damit es nicht laggt
                if (idx >= d.pathIndex() - 2 && idx <= d.pathIndex() + PATH_TILES) {
                    BlockPos b = BlockPos.of(path[i]);
                    int fill = (idx == d.pathIndex() ? 0x70 : 0x38) << 24 | (color & 0xFFFFFF);
                    Gizmos.cuboid(new AABB(b.getX() + 0.08, b.getY(), b.getZ() + 0.08, b.getX() + 0.92, b.getY() + 0.12, b.getZ() + 0.92),
                            GizmoStyle.strokeAndFill(color, 2f, fill)).setAlwaysOnTop();
                }
                prev = next;
            }
        }
        if (d.goal() != BotDebug.Detail.NO_POS)
            Gizmos.cuboid(BlockPos.of(d.goal()), GizmoStyle.strokeAndFill(YELLOW, 2f, 0x40FFE060)).setAlwaysOnTop();
        for (long o : d.obstacles())
            Gizmos.cuboid(BlockPos.of(o), GizmoStyle.strokeAndFill(RED, 2f, 0x40FF4040)).setAlwaysOnTop();
    }

    private static Vec3 pathPoint(long packed) {
        BlockPos p = BlockPos.of(packed);
        return new Vec3(p.getX() + 0.5, p.getY() + 0.15, p.getZ() + 0.5);
    }
}
