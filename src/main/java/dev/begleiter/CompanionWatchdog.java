package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * Baustein Wächter gegen Stillstand: hängende Aufgaben, Planer-Endlosschleifen, unerreichbare Kampfziele und
 * langes Nichtstun erkennen und melden; dazu die Diagnosezeilen. Nur Laufzeit.
 */
public final class CompanionWatchdog {
    private final CompanionEntity e;
    private int watchSig;
    private Task watchTask;
    private double watchBest;
    private int watchSince;
    private int lastPlanSig;
    private int planRepeats;
    private int lastWorkTick;
    private int targetSince = -1;
    private int ignoreFarTargetsUntil;

    CompanionWatchdog(CompanionEntity e) { this.e = e; }

    /** Hat sich bei vorhandenen Aufgaben 20 Sekunden lang nichts verändert, wird die Aufgabe gemeldet und übersprungen. */
    void progressWatch() {
        var tasks = e.queue();
        // Schmelzen: steht lange am Ofen, ohne dass sich die Item-Zahl ändert (Erz -> Barren) – das ist kein Hänger
        if (tasks.isEmpty() || e.isPaused() || e.getTarget() != null || e.isRetreating() || tasks.peekFirst().type() == TaskType.SMELT) {
            watchSince = e.tickCount;
            return;
        }
        if (tasks.peekFirst().spec() instanceof TaskSpec.Request r && (r.phase() == 1 || r.phase() == 2)
                && (e.getOwner() == null || e.distanceToSqr(e.getOwner()) <= 6.25)) {
            watchSince = e.tickCount;
            return;
        }
        // Aufgabe mit Ziel: nur näher kommen zählt (im Kreis laufen ist kein Fortschritt);
        // sonst nur waagerecht: auf der Stelle springen (z.B. auf einer Fackel) ist kein Fortschritt
        BlockPos p = e.blockPosition();
        Task cur = tasks.peekFirst();
        int move;
        if (cur.pos() != null && cur.type() != TaskType.BUILD) {
            double d = Math.sqrt(cur.pos().distSqr(p));
            if (cur != watchTask) { watchTask = cur; watchBest = d; }
            if (d < watchBest - 1.0) watchBest = d;
            move = (int) Math.round(watchBest * 4);
        } else {
            watchTask = null;
            move = java.util.Objects.hash(p.getX(), p.getZ());
        }
        int sig = java.util.Objects.hash(cur, move, e.totalItems(), tasks.size(),
                e.build == null ? -1 : e.build.index, e.scaffolding.size());
        if (sig != watchSig) { watchSig = sig; watchSince = e.tickCount; return; }
        if (e.tickCount - watchSince > 400) {
            Task h = tasks.peekFirst();
            if (e.journey.running()) {
                e.journey.fail();
                watchSince = e.tickCount;
                return;
            }
            if (e.work.active()) {
                e.work.taskFailed();
                watchSince = e.tickCount;
                return;
            }
            e.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED, h);
            if (e.hasRecipeRequest()) {
                e.pauseRecipeRequest(CompanionTexts.tr("request.interrupted", CompanionTexts.task(h)));
            } else if (e.goal == CompanionNeeds.Need.SUPPLY || e.goal == CompanionNeeds.Need.ORDER) {
                // nach einer gescheiterten Voraussetzung sind die abhängigen eigenen Aufgaben ungültig
                e.planFeedback.finish(e, false);
                e.cancelAll();
                e.plannerCooldownUntil = e.tickCount + 200;
            } else tasks.pollFirst();
            e.problem(EventKind.DANGER, CompanionTexts.tr("log.problem.stalled_task", CompanionTexts.task(h), CompanionTexts.tr(e.getNavigation().isDone() ? "log.nav.done" : "log.nav.running")));
            watchSince = e.tickCount;
        }
    }

    /** Wird vom Planer bei jeder Entscheidung gerufen: erkennt Endlosschleifen ohne Veränderung. */
    public void noteStage(Component msg) {
        int sig = java.util.Objects.hash(msg, e.blockPosition(), e.totalItems());
        if (sig == lastPlanSig) planRepeats++; else { lastPlanSig = sig; planRepeats = 0; }
        if (planRepeats >= 5) {
            planRepeats = 0;
            e.problem(EventKind.DANGER, CompanionTexts.tr("log.problem.loop", msg));
            e.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED, e.currentTask());
            e.planFeedback.finish(e, false);
            e.queue().clear();
            Task ex = e.explorePoint();
            if (ex != null) e.addTask(ex);
            e.plannerCooldownUntil = e.tickCount + 100;
        }
    }

    /** Bewusstes Warten (z.B. nachts im Haus) ist keine Endlosschleife. */
    /** Bewusstes Warten (Nachtlager, Schutz): kein Hänger, also auch keine Leerlauf-Meldung. */
    public void noteWaiting() { planRepeats = 0; lastWorkTick = e.tickCount; }

    /** Ziel-Timeout: nicht ewig an einem unerreichbaren Monster hängen bleiben. */
    void targetTick() {
        LivingEntity tgt = e.getTarget();
        if (tgt != null) {
            if (targetSince < 0) targetSince = e.tickCount;
            boolean far = e.distanceToSqr(tgt) > 36.0;
            if (e.tickCount < ignoreFarTargetsUntil && far && tgt != e.huntTarget) {
                e.setTarget(null);
                targetSince = -1;
            } else if (!tgt.isAlive() || e.distanceToSqr(tgt) > 24.0 * 24.0 || e.tickCount - targetSince > 500) {
                if (tgt.isAlive() && e.tickCount - targetSince > 500) {
                    e.problem(CompanionTexts.tr("log.problem.target", tgt.getName()));
                    ignoreFarTargetsUntil = e.tickCount + 600;
                }
                if (tgt == e.huntTarget) e.huntTarget = null;
                e.setTarget(null);
                targetSince = -1;
            }
        } else {
            targetSince = -1;
        }
    }

    /** Wachhund: wenn er lange nichts tut, im Chat sagen warum. */
    void idleTick(boolean busy, boolean autonomousIdle) {
        if (busy) lastWorkTick = e.tickCount;
        if (autonomousIdle && e.tickCount % 200 == 0 && e.tickCount - lastWorkTick > 600) {
            e.problem(e.idleDiagnosticComponent());
            lastWorkTick = e.tickCount;
        }
    }

    public List<Component> debugComponents() {
        List<Component> l = new ArrayList<>();
        l.add(CompanionTexts.tr("diagnostic.position", e.blockPosition().toShortString(), CompanionTexts.mode(e),
                e.isPausedByOwner() ? CompanionTexts.tr("status.paused") : Component.empty()));
        l.add(CompanionTexts.tr("diagnostic.health", (int) e.getHealth(), (int) e.getMaxHealth(), e.food.level(), e.foodCount()));
        l.add(CompanionTexts.tr("diagnostic.planner", e.lastStage.isEmpty() ? Component.literal("-") : e.stageComponent(),
                Math.max(0, e.plannerCooldownUntil - e.tickCount)));
        int i = 0;
        for (Task t : e.queue()) { if (i++ >= 6) break; l.add(CompanionTexts.tr("diagnostic.task", i, CompanionTexts.task(t))); }
        l.add(CompanionTexts.tr("diagnostic.tasks", e.taskCount(), e.getTarget() == null
                ? CompanionTexts.tr("diagnostic.no_target") : e.getTarget().getName()));
        if (e.planFeedback.last() != null) l.add(CompanionTexts.tr("diagnostic.feedback", CompanionTexts.literal(e.planFeedback.last().toString())));
        l.add(CompanionTexts.tr("diagnostic.navigation", CompanionTexts.tr(e.getNavigation().isDone() ? "log.nav.done" : "log.nav.running"),
                CompanionTexts.tr(e.getNavigation().getPath() == null ? "diagnostic.no_path" : "diagnostic.has_path")));
        l.add(e.nav.debugComponent());
        if (e.nav.trace.enabled()) l.add(CompanionTexts.tr("diagnostic.motion", CompanionTexts.literal(e.nav.trace.metrics().toString())));
        l.add(CompanionTexts.tr("diagnostic.hazard", CompanionTexts.hazards(e), e.problemText.isEmpty() ? Component.literal("-") : e.problemComponent()));
        return l;
    }
}
