package dev.begleiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.task.TaskSpec;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Baustein Protokoll: Spiel-Log (Ringpuffer), Server-Konsole, Problemmeldungen und die Statuszeilen (Planstufe,
 * Zielbegründung, Herstellproblem, Menü-Rückmeldung). Nur Laufzeit.
 * <p>Die Klartext-Felder ({@code lastStage}, {@code problemText} …) bleiben an der Entity, weil Menü, Tests und
 * LLM-Kontext sie lesen; hier liegen die übersetzbaren Fassungen und woher sie stammen.
 */
public final class CompanionLog {
    public static final class Entry {
        private int tick;
        private final char level;
        private final Component text;
        private final EventKind kind;
        private int count = 1;
        Entry(int tick, char level, String text) {
            this(tick, level, CompanionTexts.literal(text), BotDebug.eventKind(text));
        }
        Entry(int tick, char level, Component text, EventKind kind) {
            this.tick = tick; this.level = level; this.text = text.copy(); this.kind = kind;
        }
        public int tick() { return tick; }
        public char level() { return level; }
        public String text() { return text.getString(); }
        public Component textComponent() { return text.copy(); }
        public EventKind kind() { return kind; }
        public int count() { return count; }
    }

    static final Logger LOG = LogUtils.getLogger();
    /**
     * Spiel-Log (Info/Warnung) und Diagnose zusätzlich in die Server-Konsole: nur in der Entwicklungsumgebung
     * (GameTests, runClient) oder mit -Dbegleiter.log.console=true. Sonst landen sie auf DEBUG; das
     * In-Game-Log und der Dump-Knopf haben sie trotzdem. Fehler gehen immer in die Konsole.
     */
    private static final boolean CONSOLE_LOG = Boolean.getBoolean("begleiter.log.console")
            || net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();

    private final CompanionEntity e;
    private final ArrayDeque<Entry> buf = new ArrayDeque<>();
    private Component lastTold = Component.empty();
    private int lastToldTick = -100000;
    private Component problemText = Component.empty();
    private String problemSource = "";
    private Component stageText = Component.empty();
    private String stageSource = "";
    private Component craftProblemText = Component.empty();
    private String craftProblemSource = "";
    private Component goalReasonText = Component.empty();
    private String goalReasonSource = "";
    private Component uiMessage = Component.empty();
    private String uiMessageSource = "";

    CompanionLog(CompanionEntity e) { this.e = e; }

    static void console(char level, String prefix, Component msg) {
        if (level != 'E' && !CONSOLE_LOG && !LOG.isDebugEnabled()) return; // Text gar nicht erst auflösen
        String text = msg.getString();
        if (level == 'E') LOG.error("{} {}", prefix, text);
        else if (!CONSOLE_LOG) LOG.debug("{} {}", prefix, text);
        else if (level == 'W') LOG.warn("{} {}", prefix, text);
        else LOG.info("{} {}", prefix, text);
    }

    /** Wiederholte Einträge behalten die neueste Zeit; Sprache und Farben entscheiden nie, ob es derselbe Eintrag ist. */
    private boolean add(char level, EventKind kind, Component msg) {
        Entry first = buf.peekFirst();
        if (first != null && first.level == level && first.kind == kind && first.text.equals(msg)) {
            first.count++;
            first.tick = e.tickCount;
            return false;
        }
        buf.addFirst(new Entry(e.tickCount, level, msg, kind));
        while (buf.size() > 150) buf.removeLast();
        return true;
    }

    void info(EventKind kind, Component msg) { if (add('I', kind, msg)) console('I', "[Begleiter]", msg); }
    void warn(EventKind kind, Component msg) { if (add('W', kind, msg)) console('W', "[Begleiter]", msg); }
    void error(EventKind kind, Component msg) { if (add('E', kind, msg)) console('E', "[Begleiter]", msg); }
    void clear() { buf.clear(); }
    int size() { return buf.size(); }

    /** Problem melden: ins Log UND (nicht zu oft) in den Chat. */
    void problem(EventKind kind, Component msg) {
        problemText = msg.copy();
        e.problemText = msg.getString();
        problemSource = e.problemText;
        e.problemTick = e.tickCount;
        warn(kind, msg);
        if (!msg.equals(lastTold) || e.tickCount - lastToldTick > 1200) {
            e.tell(CompanionTexts.tr("problem", msg));
            lastTold = msg.copy();
            lastToldTick = e.tickCount;
        }
    }

    Component problemComponent() {
        return e.problemText.equals(problemSource) ? problemText.copy() : CompanionTexts.literal(e.problemText);
    }

    /** Wiederholt sich die Leerlauf-Meldung, den ursprünglichen Grund behalten statt Meldungen ineinander zu schachteln. */
    Component idleDiagnosticComponent() {
        Component previous = problemComponent();
        Component detail;
        if (previous.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                && tr.getKey().equals("server.begleiter.entity.idle") && tr.getArgs().length == 2
                && tr.getArgs()[1] instanceof Component priorDetail) {
            detail = priorDetail.copy();
        } else {
            detail = e.problemText.isEmpty() ? Component.empty() : CompanionTexts.tr("entity.idle.previous", previous);
        }
        return CompanionTexts.tr("entity.idle", e.lastStage.isEmpty() ? CompanionTexts.tr("entity.idle.no_plan") : stageComponent(), detail);
    }

    /** Neueste zuerst. errorsOnly: nur Warnungen und Fehler. */
    List<Entry> entries(int offset, int count, boolean errorsOnly) {
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        for (Entry entry : buf) {
            if (errorsOnly && entry.level() == 'I') continue;
            if (skipped++ < offset) continue;
            out.add(entry);
            if (out.size() >= count) break;
        }
        return out;
    }

    static Component format(Entry entry) {
        return CompanionTexts.logLine(entry.level(), Math.max(0, entry.tick() / 20), entry.count(), entry.textComponent());
    }

    // ------------------------------------------------------------------ Statuszeilen

    void setUiFeedback(Component message) {
        uiMessage = message.copy();
        e.lastUiAction = message.getString();
        uiMessageSource = e.lastUiAction;
    }

    Component uiFeedbackComponent() {
        return e.lastUiAction.equals(uiMessageSource) ? uiMessage.copy() : CompanionTexts.literal(e.lastUiAction);
    }

    void setGoalReason(Component reason) {
        goalReasonText = reason.copy();
        e.goalReason = reason.getString();
        goalReasonSource = e.goalReason;
    }

    Component goalReasonComponent() {
        return e.goalReason.equals(goalReasonSource) ? goalReasonText.copy() : CompanionTexts.literal(e.goalReason);
    }

    void setStage(Component stage) {
        stageText = stage.copy();
        e.lastStage = stage.getString();
        stageSource = e.lastStage;
    }

    Component stageComponent() {
        return e.lastStage.equals(stageSource) ? stageText.copy() : CompanionTexts.literal(e.lastStage);
    }

    void setCraftProblem(Component reason) {
        craftProblemText = reason.copy();
        e.lastCraftProblem = reason.getString();
        craftProblemSource = e.lastCraftProblem;
    }

    Component craftProblemComponent() {
        return e.lastCraftProblem.equals(craftProblemSource) ? craftProblemText.copy() : CompanionTexts.literal(e.lastCraftProblem);
    }

    /** Planer-Prüfungen vergleichen den Schlüssel der Stufe, nicht den übersetzten Text. */
    boolean isStage(String key) {
        return stageComponent().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                && tr.getKey().equals("server.begleiter." + key);
    }

    // ------------------------------------------------------------------ Aufgaben beschreiben

    static String describe(Task t) {
        String pos = t.pos() == null ? "" : " " + t.pos().toShortString();
        // Suchen ist nicht Abbauen: "MINE diamant x5" las sich wie "baut gerade Diamanterz ab"
        if (t.spec() instanceof TaskSpec.ResourceGoal goal && goal.resource() != null)
            return t.type() + " looking for " + goal.resource() + (goal.count() > 0 ? ", " + goal.count() + " left" : "") + pos;
        return t.type() + details(t.spec()) + pos;
    }

    /** Kurzangaben je Aufgabe für Log und Anzeige, etwa " bucket x2". */
    private static String details(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Give g -> amount(g.what(), g.max());
            case TaskSpec.Take tk -> amount(tk.item(), tk.count());
            case TaskSpec.Craft c -> amount(c.item(), c.count());
            case TaskSpec.Smelt s -> amount(s.item(), s.count());
            case TaskSpec.Station s -> amount(s.item(), 0) + (s.portable() ? " (portable)" : "");
            case TaskSpec.Repair r -> r.belowPercent() > 0 ? " below " + r.belowPercent() + " %" : "";
            case TaskSpec.GearUp g -> " tier " + g.tier() + amount(g.lastPiece(), g.attempts());
            case TaskSpec.Door d -> amount(d.facing(), 0);
            case TaskSpec.ResourceGoal g -> amount(g.resource(), g.count());
            case TaskSpec.Tree tr -> amount(null, tr.logs());
            case TaskSpec.Hunt hu -> amount(null, hu.food());
            case TaskSpec.Fetch f -> amount(f.animal(), 0);
            case TaskSpec.Fight f -> amount(f.loot() == null ? f.mob() : f.mob() + " -> " + f.loot(), f.lootCount());
            case TaskSpec.Breed b -> amount(b.species(), b.pairs());
            case TaskSpec.Fish f -> amount(null, f.catches());
            case TaskSpec.Trade tr -> amount(tr.item(), tr.count());
            case TaskSpec.Extend ex -> amount(null, ex.annex());
            default -> "";
        };
    }

    private static String amount(String what, int n) {
        return (what == null ? "" : " " + what) + (n > 0 ? " x" + n : "");
    }
}
