package dev.begleiter.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.begleiter.CompanionDebugOverlay;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebugClient;
import dev.begleiter.debug.BotDebugConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Debug-Menü der Begleiter (Standard F8): Bot-Auswahl und Suchfeld links bleiben in allen Tabs sichtbar, rechts der
 * Tab-Inhalt. Zeigt nur Server-Schnappschüsse; jede Änderung geht als Befehl an den Server, der die Berechtigung prüft.
 * Feste Texte kommen aus {@code debug.begleiter.*} in der Minecraft-Sprache; Serverwerte werden unverändert gezeigt.
 */
public final class BotDebugScreen extends Screen {
    private enum Tab {
        OVERVIEW("debug.begleiter.tab.overview"), AI("debug.begleiter.tab.ai"), NAV("debug.begleiter.tab.nav"),
        CONTROL("debug.begleiter.tab.control"), LOGS("debug.begleiter.tab.logs"), DISPLAY("debug.begleiter.tab.display");
        final String key;
        Tab(String key) { this.key = key; }
        String title() { return t(key); }
    }

    private record Line(String label, String text, int color) {}
    private record Pending(String question, Runnable run) {}

    /** Platz im Tab-Button für Rand und das "›" des gewählten Tabs. */
    private static final int SIDEBAR = 118, PAD = 6, ROW = 11, LIST_ROW = 18, BUTTON_H = 20, GAP = 4, TAB_PAD = 32;
    private static Tab lastTab = Tab.OVERVIEW;

    private Tab tab = lastTab;
    private EditBox search;
    private String botFilter = "", goalX = "", goalY = "", goalZ = "", hpText = "", hungerText = "";
    private int px, py, pw, ph;
    private int botScroll, textScroll;
    private boolean dirty;
    private Pending pending;
    private int logFilter; // 0 alle, 1 ab Warnung, 2 nur Fehler
    private List<BotDebug.LogLine> logFrozen;
    private String localNotice = "";
    private int localNoticeUntil;
    private int visibleLines;
    private int totalLines;

    public BotDebugScreen() {
        super(Component.translatable("debug.begleiter.title"));
    }

    private static String t(String key, Object... args) { return GuiText.tr(key, args); }

    private static String onOff(boolean on) { return t(on ? "debug.begleiter.on" : "debug.begleiter.off"); }

    /** Der Pfad wird nur gesendet, wenn er gebraucht wird (Navigations-Tab oder Option). */
    public boolean wantsPath() { return tab == Tab.NAV; }

    @Override public boolean isPauseScreen() { return false; }

    private int cx() { return px + SIDEBAR + PAD * 2; }
    private int cy() { return py + 32; }
    private int cw() { return pw - SIDEBAR - PAD * 3; }
    private int ch() { return ph - 38; }

    // ------------------------------------------------------------------ Aufbau

    @Override protected void init() {
        pw = Math.min(480, width - 8);
        ph = Math.min(260, height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        build();
    }

    private void build() {
        clearWidgets();
        dirty = false;
        search = new EditBox(font, px + PAD, py + 32, SIDEBAR, 18, Component.translatable("debug.begleiter.search"));
        search.setHint(Component.translatable("debug.begleiter.search.hint"));
        search.setMaxLength(32);
        search.setValue(botFilter);
        search.setResponder(v -> { botFilter = v; botScroll = 0; });
        addRenderableWidget(search);
        // Tab-Breiten nach Textlänge, bei zu wenig Platz anteilig verkleinert
        int avail = pw - PAD * 2 - GAP * (Tab.values().length - 1), wanted = 0;
        for (Tab tb : Tab.values()) wanted += font.width(tb.title()) + TAB_PAD;
        int x = px + PAD;
        for (Tab tb : Tab.values()) {
            final Tab target = tb;
            int w = (font.width(tb.title()) + TAB_PAD) * Math.min(avail, wanted) / wanted;
            addRenderableWidget(new ControlButton(x, py + 6, w, tb.title(), ItemStack.EMPTY,
                    tb.title(), () -> tab == target, () -> { tab = target; lastTab = target; textScroll = 0; pending = null; dirty = true; }));
            x += w + GAP;
        }
        if (pending != null) { buildPending(); return; }
        switch (tab) {
            case NAV -> buildNav();
            case CONTROL -> buildControl();
            case LOGS -> buildLogs();
            case DISPLAY -> buildDisplay();
            default -> { }
        }
    }

    private ControlButton button(int x, int y, int w, String label, String tip, java.util.function.BooleanSupplier on, Runnable r) {
        ControlButton b = new ControlButton(x, y, w, label, ItemStack.EMPTY, tip, on, r);
        addRenderableWidget(b);
        return b;
    }

    private ControlButton action(int x, int y, int w, String label, String tip, Runnable r) {
        ControlButton b = button(x, y, w, label, tip, () -> false, r);
        b.active = BotDebugClient.selectedEntry() != null;
        return b;
    }

    private EditBox box(int x, int y, int w, String hint, String value, java.util.function.Consumer<String> sink) {
        EditBox e = new EditBox(font, x, y, w, 18, Component.literal(hint));
        e.setHint(Component.literal(hint));
        e.setMaxLength(8);
        e.setValue(value);
        e.setResponder(sink);
        addRenderableWidget(e);
        return e;
    }

    private void buildNav() {
        BotDebugConfig cfg = BotDebugConfig.get();
        button(cx(), cy() + ch() - BUTTON_H - 2, 160, t("debug.begleiter.toggle", t("debug.begleiter.nav.path"), onOff(cfg.path)),
                t("debug.begleiter.nav.path.tooltip"), () -> cfg.path,
                () -> { cfg.path = !cfg.path; cfg.save(); dirty = true; });
    }

    private void buildLogs() {
        int w = (cw() - GAP * 3) / 4, y = cy() + ch() - BUTTON_H - 2;
        String level = t(new String[] {"debug.begleiter.logs.filter.all", "debug.begleiter.logs.filter.warn",
                "debug.begleiter.logs.filter.error"}[logFilter]);
        button(cx(), y, w, t("debug.begleiter.logs.filter", level), t("debug.begleiter.logs.filter.tooltip"),
                () -> logFilter > 0, () -> { logFilter = (logFilter + 1) % 3; textScroll = 0; dirty = true; });
        button(cx() + w + GAP, y, w, t(logFrozen != null ? "debug.begleiter.logs.resume" : "debug.begleiter.logs.pause"),
                t("debug.begleiter.logs.pause.tooltip"),
                () -> logFrozen != null, () -> { logFrozen = logFrozen != null ? null : new ArrayList<>(currentLog()); dirty = true; });
        button(cx() + 2 * (w + GAP), y, w, t("debug.begleiter.logs.copy"), t("debug.begleiter.logs.copy.tooltip"), () -> false, () -> {
            StringBuilder sb = new StringBuilder();
            for (var line : filteredLog()) sb.append(line.formatted().getString()).append('\n');
            minecraft.keyboardHandler.setClipboard(sb.toString());
            notice(t("debug.begleiter.logs.copied", filteredLog().size()));
        });
        button(cx() + 3 * (w + GAP), y, w, t("debug.begleiter.logs.dump"), t("debug.begleiter.logs.dump.tooltip"),
                () -> false, () -> notice(BotDebugClient.dump()));
    }

    private void buildControl() {
        int w3 = (cw() - GAP * 2) / 3, w2 = (cw() - GAP) / 2, y = cy() + 16;
        action(cx(), y, w3, t("debug.begleiter.control.pause"), t("debug.begleiter.control.pause.tooltip"),
                () -> BotDebugClient.send(BotDebug.A_PAUSE, 0, 0, 0));
        action(cx() + w3 + GAP, y, w3, t("debug.begleiter.control.resume"), t("debug.begleiter.control.resume.tooltip"),
                () -> BotDebugClient.send(BotDebug.A_RESUME, 0, 0, 0));
        action(cx() + 2 * (w3 + GAP), y, w3, t("debug.begleiter.control.step"), t("debug.begleiter.control.step.tooltip"),
                () -> BotDebugClient.send(BotDebug.A_STEP, 0, 0, 0));
        y += BUTTON_H + GAP;
        String name = botName();
        action(cx(), y, w2, t("debug.begleiter.control.cancel"), t("debug.begleiter.control.cancel.tooltip"),
                () -> ask(t("debug.begleiter.control.cancel.ask", name), () -> BotDebugClient.send(BotDebug.A_CANCEL, 0, 0, 0)));
        action(cx() + w2 + GAP, y, w2, t("debug.begleiter.control.teleport"), t("debug.begleiter.control.teleport.tooltip"),
                () -> ask(t("debug.begleiter.control.teleport.ask", name), () -> BotDebugClient.send(BotDebug.A_TELEPORT, 0, 0, 0)));
        y += BUTTON_H + 18;
        if (goalX.isEmpty() && minecraft.player != null) {
            goalX = String.valueOf(minecraft.player.getBlockX());
            goalY = String.valueOf(minecraft.player.getBlockY());
            goalZ = String.valueOf(minecraft.player.getBlockZ());
        }
        int bw = 50;
        box(cx(), y, bw, "X", goalX, v -> goalX = v);
        box(cx() + bw + GAP, y, bw, "Y", goalY, v -> goalY = v);
        box(cx() + 2 * (bw + GAP), y, bw, "Z", goalZ, v -> goalZ = v);
        int rest = cx() + 3 * (bw + GAP);
        int half = (cx() + cw() - rest - GAP) / 2;
        action(rest, y - 1, half, t("debug.begleiter.control.goal.set"), t("debug.begleiter.control.goal.set.tooltip"), this::sendGoal);
        action(rest + half + GAP, y - 1, half, t("debug.begleiter.control.goal.block"), t("debug.begleiter.control.goal.block.tooltip"), () -> {
            if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos p = hit.getBlockPos();
                goalX = String.valueOf(p.getX()); goalY = String.valueOf(p.getY() + 1); goalZ = String.valueOf(p.getZ());
                dirty = true;
            } else notice(t("debug.begleiter.control.goal.no_block"));
        });
        y += 18 + 18;
        int half2 = (cw() - GAP) / 2, inputW = 46;
        String health = t("debug.begleiter.control.health"), hunger = t("debug.begleiter.control.hunger");
        box(cx(), y, inputW, health, hpText, v -> hpText = v);
        action(cx() + inputW + GAP, y - 1, half2 - inputW - GAP, t("debug.begleiter.control.health.set"),
                t("debug.begleiter.control.health.set.tooltip"), () -> setStat(health, hpText, 1, 1000, BotDebug.A_HEALTH));
        box(cx() + half2 + GAP, y, inputW, hunger, hungerText, v -> hungerText = v);
        action(cx() + half2 + GAP + inputW + GAP, y - 1, half2 - inputW - GAP, t("debug.begleiter.control.hunger.set"),
                t("debug.begleiter.control.hunger.set.tooltip"), () -> setStat(hunger, hungerText, 0, 20, BotDebug.A_HUNGER));
    }

    private void buildPending() {
        int w = (cw() - GAP) / 2, y = cy() + 70;
        Pending p = pending;
        button(cx(), y, w, t("debug.begleiter.confirm"), t("debug.begleiter.confirm.tooltip"), () -> false,
                () -> { pending = null; p.run().run(); dirty = true; });
        button(cx() + w + GAP, y, w, t("debug.begleiter.cancel"), t("debug.begleiter.cancel.tooltip"), () -> false,
                () -> { pending = null; dirty = true; });
    }

    private void buildDisplay() {
        BotDebugConfig c = BotDebugConfig.get();
        int w = (cw() - GAP) / 2, step = BUTTON_H + GAP, x2 = cx() + w + GAP, y = cy() + 2;
        toggle(cx(), y, w, "debug.begleiter.display.hud", c.hud, () -> c.hud = !c.hud);
        String esp = t(new String[] {"debug.begleiter.display.esp.off", "debug.begleiter.display.esp.selected",
                "debug.begleiter.display.esp.all"}[c.esp.ordinal()]);
        button(x2, y, w, t("debug.begleiter.display.esp", esp), t("debug.begleiter.display.esp.tooltip"),
                () -> c.esp != BotDebugConfig.Esp.OFF, () -> {
                    c.esp = BotDebugConfig.Esp.values()[(c.esp.ordinal() + 1) % BotDebugConfig.Esp.values().length];
                    c.save(); dirty = true; });
        y += step;
        toggle(cx(), y, w, "debug.begleiter.display.lines", c.lines, () -> c.lines = !c.lines);
        boolean center = c.lineOrigin == BotDebugConfig.LineOrigin.CENTER;
        button(x2, y, w, t("debug.begleiter.display.line_origin",
                        t(center ? "debug.begleiter.display.line_origin.center" : "debug.begleiter.display.line_origin.bottom")),
                t("debug.begleiter.display.line_origin.tooltip"), () -> false, () -> {
                    c.lineOrigin = center ? BotDebugConfig.LineOrigin.BOTTOM : BotDebugConfig.LineOrigin.CENTER;
                    c.save(); dirty = true; });
        y += step;
        toggle(cx(), y, w, "debug.begleiter.display.name_tags", c.nameTags, () -> c.nameTags = !c.nameTags);
        toggle(x2, y, w, "debug.begleiter.display.tag_distance", c.tagDistance, () -> c.tagDistance = !c.tagDistance);
        y += step;
        toggle(cx(), y, w, "debug.begleiter.display.tag_status", c.tagStatus, () -> c.tagStatus = !c.tagStatus);
        toggle(x2, y, w, "debug.begleiter.display.path", c.path, () -> c.path = !c.path);
        y += step;
        button(cx(), y, w, t("debug.begleiter.display.range", c.range), t("debug.begleiter.display.range.tooltip"),
                () -> false, () -> { c.range = c.nextRange(); c.save(); dirty = true; });
        button(x2, y, w, t("debug.begleiter.display.hud_reset"), t("debug.begleiter.display.hud_reset.tooltip"), () -> false, () -> {
            c.hudX = 4; c.hudY = 4; c.hudScale = 1f; c.save(); dirty = true; });
        y += step;
        int q = (cw() - GAP * 3) / 4;
        button(cx(), y, q, t("debug.begleiter.display.bigger"), t("debug.begleiter.display.bigger.tooltip"), () -> false,
                () -> { c.hudScale = Math.min(3f, c.hudScale + 0.1f); c.save(); dirty = true; });
        button(cx() + q + GAP, y, q, t("debug.begleiter.display.smaller"), t("debug.begleiter.display.smaller.tooltip"), () -> false,
                () -> { c.hudScale = Math.max(0.5f, c.hudScale - 0.1f); c.save(); dirty = true; });
        button(cx() + 2 * (q + GAP), y, q, t("debug.begleiter.display.left"), t("debug.begleiter.display.left.tooltip"), () -> false,
                () -> moveHud(c, -10, 0));
        button(cx() + 3 * (q + GAP), y, q, t("debug.begleiter.display.right"), t("debug.begleiter.display.right.tooltip"), () -> false,
                () -> moveHud(c, 10, 0));
        y += step;
        button(cx(), y, q, t("debug.begleiter.display.up"), t("debug.begleiter.display.up.tooltip"), () -> false, () -> moveHud(c, 0, -10));
        button(cx() + q + GAP, y, q, t("debug.begleiter.display.down"), t("debug.begleiter.display.down.tooltip"), () -> false,
                () -> moveHud(c, 0, 10));
    }

    /** Schalter "Name: AN/AUS"; der Tooltip liegt unter {@code <key>.tooltip}. */
    private void toggle(int x, int y, int w, String key, boolean on, Runnable flip) {
        button(x, y, w, t("debug.begleiter.toggle", t(key), onOff(on)), t(key + ".tooltip"), () -> on,
                () -> { flip.run(); BotDebugConfig.get().save(); dirty = true; });
    }

    private void moveHud(BotDebugConfig c, int dx, int dy) {
        c.hudX = Math.clamp(c.hudX + dx, 0, Math.max(0, width - 20));
        c.hudY = Math.clamp(c.hudY + dy, 0, Math.max(0, height - 20));
        c.save();
    }

    // ------------------------------------------------------------------ Aktionen

    private String botName() {
        BotDebug.Entry e = BotDebugClient.selectedEntry();
        return e == null ? t("debug.begleiter.ov.bot") : e.name();
    }

    private void ask(String question, Runnable run) {
        pending = new Pending(question, run);
        dirty = true;
    }

    private void notice(String text) {
        localNotice = text;
        localNoticeUntil = (minecraft.player == null ? 0 : minecraft.player.tickCount) + 80;
    }

    private Integer parse(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            notice(t("debug.begleiter.control.invalid", what));
            return null;
        }
    }

    private void sendGoal() {
        Integer x = parse(goalX, "X"), y = parse(goalY, "Y"), z = parse(goalZ, "Z");
        if (x != null && y != null && z != null) BotDebugClient.send(BotDebug.A_GOTO, x, y, z);
    }

    private void setStat(String what, String text, int min, int max, int action) {
        Integer v = parse(text, what);
        if (v == null) return;
        int value = Math.clamp(v, min, max);
        ask(t("debug.begleiter.control.stat.ask", what, botName(), value), () -> BotDebugClient.send(action, value, 0, 0));
    }

    // ------------------------------------------------------------------ Inhalt

    private static Line kv(String labelKey, String text, int color) { return new Line(labelKey.isEmpty() ? "" : t(labelKey), text, color); }
    private static Line info(String key, int color) { return new Line("", t(key), color); }
    private static Line head(String text) { return new Line("", text, CompanionDebugOverlay.CYAN); }

    private List<Line> lines() {
        List<Line> out = new ArrayList<>();
        if (BotDebugClient.denied()) {
            out.add(info("debug.begleiter.info.denied", CompanionDebugOverlay.RED));
            return out;
        }
        if (!BotDebugClient.hasData()) {
            out.add(info("debug.begleiter.info.no_data", CompanionDebugOverlay.GRAY));
            return out;
        }
        BotDebug.Entry e = BotDebugClient.selectedEntry();
        if (e == null) {
            out.add(info("debug.begleiter.info.no_bot", CompanionDebugOverlay.GRAY));
            return out;
        }
        BotDebug.Detail d = BotDebugClient.detail();
        if (d == null) {
            out.add(info("debug.begleiter.info.loading", CompanionDebugOverlay.GRAY));
            return out;
        }
        switch (tab) {
            case OVERVIEW -> overview(out, e, d);
            case AI -> ai(out, e, d);
            case NAV -> nav(out, e, d);
            case LOGS -> { }
            default -> { }
        }
        return out;
    }

    private void overview(List<Line> o, BotDebug.Entry e, BotDebug.Detail d) {
        o.add(kv("debug.begleiter.ov.bot", e.name(), CompanionDebugOverlay.YELLOW));
        o.add(kv("debug.begleiter.ov.status", CompanionDebugOverlay.stateText(e.state()) + " · " + d.mode(), CompanionDebugOverlay.stateColor(e.state())));
        o.add(kv("debug.begleiter.ov.dimension", e.dim(), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.ov.position", String.format(Locale.ROOT, "%.1f / %.1f / %.1f", e.x(), e.y(), e.z()) + distance(e), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.ov.health", Math.round(e.health()) + " / " + Math.round(e.maxHealth()), e.health() / Math.max(1f, e.maxHealth()) > 0.5f ? CompanionDebugOverlay.GREEN : CompanionDebugOverlay.RED));
        o.add(kv("debug.begleiter.ov.hunger", e.hunger() + " / 20", e.hunger() > 8 ? CompanionDebugOverlay.GREEN : CompanionDebugOverlay.ORANGE));
        o.add(kv("debug.begleiter.ov.resources", t("debug.begleiter.ov.resources.value", d.diamond(), d.iron(), d.coal()), CompanionDebugOverlay.WHITE));
        if (!d.mine().isEmpty()) o.add(kv("debug.begleiter.ov.mine", d.mine(), CompanionDebugOverlay.GRAY));
        o.add(kv("debug.begleiter.ov.uptime", CompanionDebugOverlay.clock(d.aliveSec()), CompanionDebugOverlay.GRAY));
        o.add(head(t("debug.begleiter.ov.equipment")));
        for (String s : d.equipment()) o.add(kv("", s, CompanionDebugOverlay.WHITE));
        o.add(head(t("debug.begleiter.ov.inventory", d.usedSlots())));
        for (String s : d.inventory()) o.add(kv("", s, CompanionDebugOverlay.WHITE));
        if (d.inventory().isEmpty()) o.add(info("debug.begleiter.ov.empty", CompanionDebugOverlay.GRAY));
    }

    private void ai(List<Line> o, BotDebug.Entry e, BotDebug.Detail d) {
        o.add(kv("debug.begleiter.ai.mission", d.mission(), CompanionDebugOverlay.YELLOW));
        if (!d.reasons().isEmpty()) o.add(kv("debug.begleiter.ai.reason", d.reasons(), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.ai.plan", d.plan(), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.ai.goal", goalText(d), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.ai.action", d.action() + "  (" + d.actionSec() + " s)", CompanionDebugOverlay.GREEN));
        if (!d.needs().isEmpty()) o.add(kv("debug.begleiter.ai.needs", d.needs(), CompanionDebugOverlay.WHITE));
        if (!d.hazard().isEmpty()) o.add(kv("debug.begleiter.ai.hazard", d.hazard(), CompanionDebugOverlay.ORANGE));
        o.add(head(t("debug.begleiter.ai.queue", d.taskTotal())));
        for (int i = 0; i < d.tasks().size(); i++)
            o.add(new Line(i == 0 ? "▶" : (i + 1) + ".", d.tasks().get(i), i == 0 ? CompanionDebugOverlay.GREEN : CompanionDebugOverlay.GRAY));
        if (d.taskTotal() > d.tasks().size())
            o.add(new Line("", t("debug.begleiter.ai.more", d.taskTotal() - d.tasks().size()), CompanionDebugOverlay.GRAY));
        if (d.taskTotal() == 0) o.add(info("debug.begleiter.ai.no_tasks", CompanionDebugOverlay.GRAY));
        o.add(head(t("debug.begleiter.ai.last_error")));
        o.add(d.lastError().isEmpty() ? info("debug.begleiter.ai.none", CompanionDebugOverlay.GRAY)
                : new Line(t("debug.begleiter.ai.error_age", d.errorAgeSec()), d.lastError(), CompanionDebugOverlay.RED));
    }

    private void nav(List<Line> o, BotDebug.Entry e, BotDebug.Detail d) {
        o.add(kv("debug.begleiter.ai.goal", goalText(d), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.pathfinding", t(!d.navActive() ? "debug.begleiter.nav.inactive"
                : d.pathLength() == 0 ? "debug.begleiter.nav.searching" : "debug.begleiter.nav.running"),
                d.navActive() ? CompanionDebugOverlay.GREEN : CompanionDebugOverlay.GRAY));
        int percent = d.pathLength() == 0 ? 0 : 100 * d.pathIndex() / d.pathLength();
        o.add(kv("debug.begleiter.nav.progress", d.pathLength() == 0 ? "-"
                : t("debug.begleiter.nav.progress.value", d.pathIndex(), d.pathLength(), percent)
                + (d.partial() ? " · " + t("debug.begleiter.nav.partial") : ""), CompanionDebugOverlay.CYAN));
        o.add(kv("debug.begleiter.nav.movement", d.moveType(), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.step_length", t("debug.begleiter.nav.blocks", String.format(Locale.ROOT, "%.1f", d.stepLength())), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.nodes", String.valueOf(d.nodes()), CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.compute", String.format(Locale.ROOT, "%.1f ms", d.ms()), d.ms() > 20 ? CompanionDebugOverlay.ORANGE : CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.last_event", d.navEvent().isEmpty() ? "-" : d.navEvent(), CompanionDebugOverlay.GRAY));
        o.add(kv("debug.begleiter.nav.failures", String.valueOf(d.failures()), d.failures() > 0 ? CompanionDebugOverlay.RED : CompanionDebugOverlay.WHITE));
        o.add(kv("debug.begleiter.nav.obstacles", d.obstacles().length == 0 ? t("debug.begleiter.nav.obstacles.none")
                : t("debug.begleiter.nav.obstacles.count", d.obstacles().length),
                d.obstacles().length == 0 ? CompanionDebugOverlay.GRAY : CompanionDebugOverlay.RED));
        for (long p : d.obstacles()) {
            BlockPos b = BlockPos.of(p);
            o.add(kv("", b.getX() + " " + b.getY() + " " + b.getZ(), CompanionDebugOverlay.RED));
        }
        if (!BotDebugConfig.get().path) o.add(info("debug.begleiter.nav.tip", CompanionDebugOverlay.GRAY));
    }

    private static String goalText(BotDebug.Detail d) {
        if (d.goal() != BotDebug.Detail.NO_POS) {
            BlockPos p = BlockPos.of(d.goal());
            return p.getX() + " " + p.getY() + " " + p.getZ();
        }
        return d.navGoal().equals("-") ? t("debug.begleiter.nav.no_goal") : d.navGoal();
    }

    private String distance(BotDebug.Entry e) {
        if (minecraft.player == null || minecraft.level == null
                || !minecraft.level.dimension().identifier().toString().equals(e.dim())) return "";
        return String.format(Locale.ROOT, "  (%.0f m)", Math.sqrt(minecraft.player.distanceToSqr(e.x(), e.y(), e.z())));
    }

    private List<BotDebug.LogLine> currentLog() {
        BotDebug.Detail d = BotDebugClient.detail();
        return d == null ? List.of() : d.logLines();
    }

    private List<BotDebug.LogLine> filteredLog() {
        List<BotDebug.LogLine> out = new ArrayList<>();
        for (var line : logFrozen != null ? logFrozen : currentLog()) {
            char lvl = line.level();
            if (logFilter == 1 && lvl == 'I') continue;
            if (logFilter == 2 && lvl != 'E') continue;
            out.add(line);
        }
        return out;
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x66060B10);
    }

    @Override public void tick() {
        super.tick();
        if (dirty) build();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (dirty) build();
        g.fill(px, py, px + pw, py + ph, ControlTheme.BACKGROUND);
        g.outline(px, py, pw, ph, ControlTheme.LINE);
        g.fill(px + SIDEBAR + PAD, py + 32, px + SIDEBAR + PAD + 1, py + ph - PAD, ControlTheme.LINE);
        drawBotList(g, mx, my);
        drawNotice(g);
        if (pending != null) {
            g.text(font, clip(pending.question(), cw()), cx(), cy() + 30, ControlTheme.TEXT, false);
            g.text(font, clip(t("debug.begleiter.confirm.irreversible"), cw()), cx(), cy() + 44, ControlTheme.MUTED, false);
        } else if (tab == Tab.CONTROL) {
            drawControlLabels(g);
        } else if (tab == Tab.DISPLAY) {
            BotDebugConfig c = BotDebugConfig.get();
            g.text(font, t("debug.begleiter.display.hud_info", c.hudX, c.hudY, String.format(Locale.ROOT, "%.0f", c.hudScale * 100)),
                    cx(), cy() + ch() - 12, ControlTheme.MUTED, false);
        } else {
            drawText(g, mx, my);
        }
        super.extractRenderState(g, mx, my, delta);
    }

    private void drawNotice(GuiGraphicsExtractor g) {
        String serverNotice = BotDebugClient.notice();
        boolean local = minecraft.player != null && minecraft.player.tickCount < localNoticeUntil;
        String text = local ? localNotice : serverNotice;
        if (!text.isEmpty()) g.text(font, clip(text, cw()), cx(), py + ph - 12 - (tab == Tab.NAV || tab == Tab.LOGS ? BUTTON_H + 4 : 0),
                ControlTheme.ACCENT, false);
    }

    private void drawControlLabels(GuiGraphicsExtractor g) {
        BotDebug.Entry e = BotDebugClient.selectedEntry();
        g.text(font, e == null ? t("debug.begleiter.control.none")
                        : t("debug.begleiter.control.header", e.name(), CompanionDebugOverlay.stateText(e.state())),
                cx(), cy() + 2, e == null ? ControlTheme.MUTED : ControlTheme.TEXT, false);
        int y = cy() + 16 + 2 * (BUTTON_H + GAP) + 4;
        g.text(font, t("debug.begleiter.control.goal.label"), cx(), y, ControlTheme.MUTED, false);
        g.text(font, t("debug.begleiter.control.stats.label"), cx(), y + 34, ControlTheme.MUTED, false);
    }

    private void drawBotList(GuiGraphicsExtractor g, int mx, int my) {
        List<BotDebug.Entry> bots = filteredBots();
        int top = py + 54, rows = (py + ph - PAD - top) / LIST_ROW;
        botScroll = Math.clamp(botScroll, 0, Math.max(0, bots.size() - rows));
        g.fill(px + PAD, top, px + PAD + SIDEBAR, py + ph - PAD, ControlTheme.PANEL);
        if (bots.isEmpty()) {
            g.text(font, t(BotDebugClient.denied() ? "debug.begleiter.list.denied" : "debug.begleiter.list.empty"),
                    px + PAD + 4, top + 5, ControlTheme.MUTED, false);
            return;
        }
        for (int i = 0; i < Math.min(rows, bots.size() - botScroll); i++) {
            BotDebug.Entry b = bots.get(botScroll + i);
            int y = top + i * LIST_ROW;
            boolean sel = b.id().equals(BotDebugClient.selected());
            boolean hover = mx >= px + PAD && mx < px + PAD + SIDEBAR && my >= y && my < y + LIST_ROW;
            if (sel || hover) g.fill(px + PAD, y, px + PAD + SIDEBAR, y + LIST_ROW, sel ? 0xFF24404A : 0xFF304455);
            if (sel) g.fill(px + PAD, y, px + PAD + 2, y + LIST_ROW, ControlTheme.ACCENT);
            g.fill(px + PAD + 6, y + 6, px + PAD + 12, y + 12, CompanionDebugOverlay.stateColor(b.state()));
            g.text(font, clip(b.name(), SIDEBAR - 20), px + PAD + 16, y + 5, sel ? ControlTheme.ACCENT : ControlTheme.TEXT, false);
        }
        if (bots.size() > rows) g.text(font, (botScroll + 1) + "–" + Math.min(bots.size(), botScroll + rows) + "/" + bots.size(),
                px + PAD + SIDEBAR - 44, py + 22, ControlTheme.MUTED, false);
    }

    private List<BotDebug.Entry> filteredBots() {
        String f = botFilter.trim().toLowerCase(Locale.ROOT);
        List<BotDebug.Entry> out = new ArrayList<>();
        for (BotDebug.Entry e : BotDebugClient.bots())
            if (f.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(f) || e.dim().contains(f)
                    || CompanionDebugOverlay.stateText(e.state()).toLowerCase(Locale.ROOT).contains(f)) out.add(e);
        return out;
    }

    private void drawText(GuiGraphicsExtractor g, int mx, int my) {
        int reserve = tab == Tab.NAV || tab == Tab.LOGS ? BUTTON_H + 16 : 14;
        int areaH = ch() - reserve;
        List<Line> lines = tab == Tab.LOGS ? logLines() : lines();
        visibleLines = Math.max(1, areaH / ROW);
        totalLines = lines.size();
        textScroll = Math.clamp(textScroll, 0, Math.max(0, totalLines - visibleLines));
        g.enableScissor(cx(), cy(), cx() + cw(), cy() + areaH);
        int labelW = 0;
        for (Line l : lines) if (!l.label().isEmpty()) labelW = Math.max(labelW, Math.min(font.width(l.label()), 84));
        for (int i = 0; i < visibleLines && textScroll + i < totalLines; i++) {
            Line l = lines.get(textScroll + i);
            int y = cy() + i * ROW, x = cx();
            if (tab == Tab.LOGS) {
                g.text(font, clip(l.text(), cw() - 8), x, y, l.color(), false);
                continue;
            }
            if (!l.label().isEmpty()) g.text(font, clip(l.label(), labelW), x, y, ControlTheme.MUTED, false);
            g.text(font, clip(l.text(), cw() - labelW - 14), x + labelW + (labelW > 0 ? 8 : 0), y, l.color(), false);
        }
        g.disableScissor();
        if (totalLines > visibleLines) {
            int trackH = areaH, barH = Math.max(8, trackH * visibleLines / totalLines);
            int barY = cy() + (trackH - barH) * textScroll / Math.max(1, totalLines - visibleLines);
            g.fill(cx() + cw() - 2, cy(), cx() + cw(), cy() + trackH, ControlTheme.PANEL);
            g.fill(cx() + cw() - 2, barY, cx() + cw(), barY + barH, ControlTheme.ACCENT);
        }
    }

    private List<Line> logLines() {
        List<Line> out = new ArrayList<>();
        if (BotDebugClient.detail() == null) return lines();
        for (var line : filteredLog()) {
            char lvl = line.level();
            int color = lvl == 'E' ? CompanionDebugOverlay.RED : lvl == 'W' ? CompanionDebugOverlay.ORANGE : CompanionDebugOverlay.WHITE;
            if (lvl == 'I') {
                int special = BotDebug.eventColor(line.kind());
                color = special != 0 ? special : CompanionDebugOverlay.GRAY;
            }
            out.add(new Line("", line.formatted().getString(), color));
        }
        if (out.isEmpty()) out.add(info("debug.begleiter.logs.empty", CompanionDebugOverlay.GRAY));
        return out;
    }

    private String clip(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return maxWidth <= 8 ? "" : font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    // ------------------------------------------------------------------ Eingabe

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            List<BotDebug.Entry> bots = filteredBots();
            int top = py + 54, rows = (py + ph - PAD - top) / LIST_ROW;
            if (event.x() >= px + PAD && event.x() < px + PAD + SIDEBAR && event.y() >= top && event.y() < py + ph - PAD) {
                int i = botScroll + (int) ((event.y() - top) / LIST_ROW);
                if (i < bots.size() && i - botScroll < rows) {
                    BotDebugClient.select(bots.get(i).id());
                    textScroll = 0;
                    logFrozen = null;
                    dirty = true;
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int step = vertical < 0 ? 1 : -1;
        if (x >= px + PAD && x < px + PAD + SIDEBAR) botScroll += step;
        else textScroll += step * 3;
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (pending != null && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            pending = null;
            dirty = true;
            return true;
        }
        boolean typing = getFocused() instanceof EditBox;
        if (!typing && BotDebugClient.menuKey != null && BotDebugClient.menuKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void onClose() {
        BotDebugConfig.get().save();
        super.onClose();
    }
}
