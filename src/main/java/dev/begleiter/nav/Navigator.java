package dev.begleiter.nav;

import java.util.Objects;
import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import dev.begleiter.CompanionEntity;

/**
 * Wegfindung mit Zeitbudget: früh losgehen auf einem festen Teilweg, bei wanderndem Ziel ersetzen, Fortsetzungen
 * rechtzeitig anhängen.
 */
public final class Navigator {
    public enum Result { RUNNING, ARRIVED, UNREACHABLE }
    private static final long FIRST_SLICE = 10_000_000L, SLICE = 4_000_000L;
    private static final int MAX_FAILURES = 5, BAD_STEP_TICKS = 20 * 60;
    private final CompanionEntity mob;
    @FunctionalInterface interface SearchFactory { PathSearch create(NavContext context, BlockPos start, Goal target); }
    private final SearchFactory searches;
    private final Object mainRequest = new Object(), aheadRequest = new Object();
    private Goal goal;
    private Object movingTarget;
    private PathSearch search, ahead;
    private NavContext searchCtx, aheadCtx;
    private PathExecutor exec;
    private int failures, emptySearches, searchStarted, planTicks = 8;
    private long generation, searchGeneration, aheadGeneration;
    private boolean checkedPath, provisional;
    private long searchNanosThisTick;
    private int drivenAt = Integer.MIN_VALUE;
    private int budgetWaitTick = Integer.MIN_VALUE;
    public final MotionTrace trace = new MotionTrace();
    public String lastEvent = "";
    private Component lastEventText = Component.empty();
    private String lastEventSource = "";
    public int lastNodes;
    public double lastMs;

    public Navigator(CompanionEntity mob) {
        this(mob, PathSearch::new);
        if (Boolean.getBoolean("begleiter.nav.trace")) trace.enable();
    }

    public void recoveryEvent(String reason) { recoveryEvent(CompanionTexts.literal(reason)); }
    public void recoveryEvent(Component reason) { event(reason); record(); }
    public Component eventComponent() {
        return lastEvent.equals(lastEventSource) ? lastEventText.copy() : CompanionTexts.literal(lastEvent);
    }
    Navigator(CompanionEntity mob, SearchFactory searches) { this.mob = mob; this.searches = searches; }
    public boolean active() { return goal != null; }
    public PathExecutor executor() { return exec; }
    public Goal goal() { return goal; }
    public int failures() { return failures; }
    public boolean searching() { return search != null; }
    public long generation() { return generation; }

    public void followChecked(Path path, NavContext context, Goal target) {
        stop(); goal = target; checkedPath = true;
        if (path.length() > 0) exec = new PathExecutor(path, context, false);
    }

    private void cancelSearches() {
        if (mob.level() instanceof ServerLevel level) {
            NavScheduler.cancel(level, mainRequest); NavScheduler.cancel(level, aheadRequest);
        }
        search = ahead = null; searchCtx = aheadCtx = null; provisional = false;
    }

    public void stop() {
        cancelSearches();
        if (exec != null) exec.cancel(mob);
        exec = null; goal = null; movingTarget = null;
        checkedPath = false; failures = emptySearches = 0; generation++;
        mob.motion.stop();
        if (mob.isPillarActive()) mob.pillarReset();
    }

    public Result goTo(Goal target) { return drive(target, null); }
    /** Dasselbe Ziel-Wesen an neuer Stelle: den laufenden Weg weitergehen, während der Ersatz gerechnet wird. */
    public Result goToMoving(Goal target, Object identity) { return drive(target, Objects.requireNonNull(identity)); }

    private Result drive(Goal target, Object identity) {
        drivenAt = mob.tickCount;
        searchNanosThisTick = 0;
        try { return driveInternal(target, identity); }
        finally { record(); }
    }

    private Result driveInternal(Goal target, Object identity) {
        if (!target.equals(goal)) {
            if (identity != null && identity.equals(movingTarget) && !checkedPath) {
                cancelSearches(); goal = target; generation++; failures = emptySearches = 0;
                event(CompanionTexts.tr("nav.event.moving_target"));
            } else {
                if (exec != null && !exec.safeToCancel(mob) && exec.status() == PathExecutor.Status.RUNNING)
                    return tick(); // erst sicher landen, mit der üblichen Fehlerbehandlung
                stop(); goal = target; movingTarget = identity; event(CompanionTexts.tr("nav.event.new_goal"));
            }
        }
        if (target.isInGoal(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ()) && (mob.onGround() || mob.isInWater())
                && (exec == null || exec.safeToCancel(mob))) {
            stop(); goal = target; movingTarget = identity; event(CompanionTexts.tr("nav.event.arrived"));
            return Result.ARRIVED;
        }
        return tick();
    }

    private Result tick() {
        if (exec != null && search != null) {
            if (!search.done()) advance(false, NavScheduler.Priority.ACTIVE, SLICE);
            if (search != null && search.done() && searchGeneration == generation) {
                Path replacement = search.path();
                if (replacement != null && replacement.length() > 0 && exec.replace(replacement.trimPartial(), searchCtx, mob)) {
                    remember(search); clearMain(); provisional = false; event(CompanionTexts.tr("nav.event.replacement_attached"));
                } else if (exec.safeToCancel(mob)) {
                    clearMain(); provisional = false; event(CompanionTexts.tr("nav.event.replacement_reanchored"));
                }
            }
        }
        if (exec != null) {
            PathExecutor.Status state = exec.tick(mob);
            if (state == PathExecutor.Status.FAILED) {
                if (checkedPath) return unreachable(CompanionTexts.tr("nav.failure.checked_changed"));
                failures++; event(CompanionTexts.tr("nav.event.failed", exec.failureComponent()));
                if (exec.failedAt != null) mob.badSteps.put(exec.failedAt, mob.tickCount + BAD_STEP_TICKS);
                exec = null; cancelSearches();
                if (failures >= MAX_FAILURES) return unreachable(CompanionTexts.tr("nav.failure.many_failures"));
            } else if (state == PathExecutor.Status.DONE) {
                if (checkedPath) return unreachable(CompanionTexts.tr("nav.failure.checked_finished"));
                exec = null;
                if (ahead != null) {
                    if (!ahead.done()) advance(true, NavScheduler.Priority.ACTIVE, SLICE);
                    search = ahead; searchCtx = aheadCtx; searchGeneration = aheadGeneration;
                    NavScheduler.cancel((ServerLevel) mob.level(), aheadRequest); ahead = null; aheadCtx = null;
                }
            } else {
                if (movingTarget != null && !goal.isInGoal(exec.path.end().getX(), exec.path.end().getY(), exec.path.end().getZ())
                        && search == null) advance(false, NavScheduler.Priority.ACTIVE, FIRST_SLICE);
                if (search == null) planAhead();
                return Result.RUNNING;
            }
        }
        if (search == null) {
            if (!mob.onGround() && !mob.isInWater()) return Result.RUNNING;
            advance(false, NavScheduler.Priority.ACTIVE, FIRST_SLICE);
        } else if (!search.done()) advance(false, NavScheduler.Priority.ACTIVE, SLICE);
        if (exec != null) { exec.tick(mob); return Result.RUNNING; }
        if (search == null || !search.done()) { mob.motion.stop(); return Result.RUNNING; }
        return adopt();
    }

    private void clearMain() {
        NavScheduler.cancel((ServerLevel) mob.level(), mainRequest); search = null; searchCtx = null;
    }
    private void remember(PathSearch value) {
        lastNodes = value.nodes(); lastMs = value.nanos() / 1e6;
        planTicks = Math.max(2, Math.min(100, mob.tickCount - searchStarted + 1));
    }
    private Result adopt() {
        if (searchGeneration != generation) { clearMain(); return Result.RUNNING; }
        remember(search);
        Path path = search.path(); NavContext context = searchCtx;
        clearMain(); provisional = false;
        if (path == null || path.length() == 0) {
            mob.motion.stop();
            if (++emptySearches >= 2) return unreachable(CompanionTexts.tr("nav.failure.no_path"));
            return Result.RUNNING;
        }
        emptySearches = 0; exec = new PathExecutor(path.trimPartial(), context);
        event(CompanionTexts.tr("nav.event.adopted", CompanionTexts.tr(path.complete ? "nav.path.full" : "nav.path.partial"), path.length()));
        exec.tick(mob);
        return Result.RUNNING;
    }

    private void planAhead() {
        if (exec == null || exec.path.complete || checkedPath || provisional) return;
        double estimatedTicks = exec.remainingCost() * 0.75;
        if (ahead == null && estimatedTicks > Math.max(60, planTicks * 2 + 12)) return;
        if (ahead == null || !ahead.done()) advance(true, NavScheduler.Priority.PREFETCH, SLICE);
        if (ahead != null && ahead.done()) {
            Path continuation = ahead.path();
            if (aheadGeneration == generation && continuation != null && continuation.length() > 0 && exec.append(continuation.trimPartial())) {
                remember(ahead); event(CompanionTexts.tr("nav.event.continuation_attached"));
            }
            NavScheduler.cancel((ServerLevel) mob.level(), aheadRequest); ahead = null; aheadCtx = null;
        }
    }

    private void advance(boolean planningAhead, NavScheduler.Priority priority, long requested) {
        var level = (ServerLevel) mob.level();
        long expected = generation;
        boolean served = NavScheduler.run(level, planningAhead ? aheadRequest : mainRequest, priority, requested, deadline -> {
            if (expected != generation) return true;
            long started = System.nanoTime();
            try {
                if (planningAhead) {
                    if (ahead == null) {
                        aheadCtx = newContext(); aheadGeneration = generation; searchStarted = mob.tickCount;
                        ahead = searches.create(aheadCtx, exec == null ? PathSearch.startFor(aheadCtx, mob) : exec.path.end(), goal);
                    }
                    long left = NavScheduler.remaining(deadline);
                    if (left > 0) ahead.step(left);
                    return ahead.done();
                }
                if (search == null) {
                    searchCtx = newContext(); searchGeneration = generation; searchStarted = mob.tickCount;
                    search = searches.create(searchCtx, PathSearch.startFor(searchCtx, mob), goal);
                }
                long left = NavScheduler.remaining(deadline);
                if (left > 0) search.step(left);
                if (!search.done() && exec == null) {
                    Path partial = search.path();
                    if (partial != null && partial.length() >= 6) {
                        exec = new PathExecutor(partial.trimPartial(), newContext());
                        provisional = true; event(CompanionTexts.tr("nav.event.partial_started"));
                    }
                }
                return search.done();
            } finally { searchNanosThisTick += System.nanoTime() - started; }
        });
        if (!served && !planningAhead) budgetWaitTick = mob.tickCount;
    }

    /** Die eigene Suche wartet in diesem Tick hinter anderen auf Rechenzeit (viele Begleiter, volle Warteschlange). */
    public boolean waitingForBudget() { return budgetWaitTick == mob.tickCount; }

    private NavContext newContext() {
        NavContext context = new NavContext(mob);
        if (goal instanceof Goal.Block b) {
            var state = context.blocks.get(b.x(), b.y(), b.z());
            context.allowPortal = state != null && state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
        }
        return context;
    }
    private Result unreachable(Component why) {
        Goal target = goal; stop(); goal = target; event(CompanionTexts.tr("nav.event.unreachable", why));
        mob.logInfo(CompanionTexts.tr("log.nav", eventComponent()));
        return Result.UNREACHABLE;
    }
    private void event(Component value) {
        lastEventText = value.copy(); lastEvent = value.getString(); lastEventSource = lastEvent;
        trace.event(lastEvent);
    }

    private void record() {
        if (!trace.enabled()) return;
        var control = mob.getMoveControl(); var current = exec == null ? null : exec.current();
        String state = exec != null && exec.steeringActive ? "WALK" : current == null ? (search != null ? "SEARCH" : "IDLE") : current.hasWorked() ? "WORK"
                : current.move.kind == Move.Kind.TRAVERSE || current.move.kind == Move.Kind.DIAGONAL ? "WALK" : current.move.kind.name();
        var owner = mob.motion.owner();
        if (owner.ordinal() >= MotionController.Owner.SWIM.ordinal()) state = owner.name();
        boolean fresh = drivenAt == mob.tickCount;
        trace.record(new MotionTrace.Sample(mob.tickCount, mob.getX(), mob.getY(), mob.getZ(), mob.getDeltaMovement().horizontalDistance(),
                mob.yBodyRot, mob.getYHeadRot(), control.getWantedX(), control.getWantedZ(), mob.motion.owner().name(), state,
                exec == null ? -1 : exec.index(), exec == null ? 0 : exec.progress(), trace.takeEvent(), fresh ? searchNanosThisTick : 0,
                exec == null || !fresh ? 0 : exec.validationNanos, exec == null || !fresh ? 0 : exec.steeringNanos));
    }
    public void sampleMotion() { record(); }
    public String debugText() {
        return debugComponent().getString();
    }
    public Component debugComponent() {
        if (goal == null) return Component.empty();
        Component text = exec == null ? CompanionTexts.tr("diagnostic.route.search")
                : CompanionTexts.tr("diagnostic.route.step", exec.index(), exec.path.length(), exec.steeringActive
                        ? CompanionTexts.tr("diagnostic.route.corridor") : exec.current() == null ? Component.empty()
                                : Component.literal(" " + exec.current().move));
        return CompanionTexts.tr("diagnostic.route", text, search != null && exec != null
                ? CompanionTexts.tr("diagnostic.route.replacement") : Component.empty(), eventComponent());
    }
    public java.util.List<BlockPos> pathPoints() { return exec == null ? java.util.List.of() : exec.path.positions; }
}
