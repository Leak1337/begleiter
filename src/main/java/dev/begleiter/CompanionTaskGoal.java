package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskContext;
import dev.begleiter.task.TaskHandler;
import dev.begleiter.task.TaskHandlers;
import dev.begleiter.task.TaskSlot;
import dev.begleiter.task.TaskSpec;

/**
 * Arbeitet die Aufgabenwarteschlange des Begleiters ab: Jeder Aufgabentyp hat einen Handler (siehe {@link TaskHandlers}),
 * {@link TaskSlot} bindet ihn an die vorderste Aufgabe. Hier bleibt nur, was alle Aufgaben teilen: Navigation,
 * Feststeck-Erkennung und Überspringen, Erze am Weg, Endstatus und Fehlerfang.
 */
public class CompanionTaskGoal extends Goal {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final CompanionEntity mob;
    private final TaskContext ctx = new Ctx();
    private final TaskSlot<Task> slot = new TaskSlot<>(TaskHandlers::create);
    private Task active;
    private int stuck, skipStreak, noPath;
    private Vec3 lastPos;
    /** Kleinster Abstand zum Ziel der Aufgabe bisher und wann er erreicht wurde (Im-Kreis-Laufen erkennen). */
    private double bestDist = Double.MAX_VALUE;
    private int bestDistTick;
    private final dev.begleiter.task.MiningKnowledge mining;
    /** MINE erneuert sich nach jedem Grabstück: nur loggen, wenn sich die Suche ändert. */
    private String lastMineLog;

    public CompanionTaskGoal(CompanionEntity mob) {
        this.mob = mob;
        this.mining = new dev.begleiter.task.MiningKnowledge(mob);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !mob.isPaused() && (mob.duty() == null || mob.dutyPos() != null) && mob.currentTask() != null
                && mob.getTarget() == null && !mob.isRetreating()
                && (!(mob.level() instanceof ServerLevel world) || mob.work.runnable(world));
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    /** Ohne das tickt Minecraft das Ziel nur jeden 2. Tick (je nach Entity-ID) und "tickCount % 10" trifft nie. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        reset();
    }

    /**
     * Kampf, Pause, leere Warteschlange …: Handler beenden (beim Weitermachen startet ein frischer) und Wegfindung
     * anhalten (sonst steuert sie noch, während die Mob-Navigation übernimmt).
     */
    @Override
    public void stop() {
        releaseHandler();
        mob.nav.stop();
    }

    private void reset() {
        mob.nav.stop();
        approachTarget = null;
        stuck = 0;
        noPath = 0;
        lastPos = null;
        bestDist = Double.MAX_VALUE;
        bestDistTick = mob.tickCount;
    }

    private BlockPos approachTarget;

    private void done() {
        releaseHandler();
        mob.finishTask();
        reset();
    }

    /** Handler stoppen; ein Fehler im stop darf die Warteschlange nicht blockieren. */
    private void releaseHandler() {
        try {
            slot.release(ctx);
        } catch (RuntimeException ex) {
            mob.logError(CompanionTexts.tr("handler.goal.stop_error", CompanionTexts.literal(String.valueOf(ex))));
        }
    }

    /** Endstatus eines Handlers umsetzen. */
    private void apply(Task t, TaskHandler.Status status) {
        if (status == TaskHandler.Status.RUNNING) return;
        if (status == TaskHandler.Status.REPLACED || mob.currentTask() != t) {
            // Warteschlange wurde umgebaut: nichts entfernen, was nicht mehr diese Aufgabe ist
            if (status != TaskHandler.Status.REPLACED)
                mob.logWarn(CompanionTexts.tr("handler.goal.changed", status.name(), CompanionTexts.task(t)));
            releaseHandler();
            reset();
            return;
        }
        if (status == TaskHandler.Status.FAILED && mob.hasRecipeRequest()) {
            releaseHandler();
            mob.pauseRecipeRequest(CompanionTexts.tr("request.paused", CompanionTexts.task(t)));
            reset();
            return;
        }
        if (status == TaskHandler.Status.FAILED && t.spec() instanceof TaskSpec.Break b && b.scoped()
                && mob.level() instanceof ServerLevel world && mob.storage.stockHarvestAllowed(world, b)) mob.noteGatherMiss(world, b.pos());
        if (status == TaskHandler.Status.FAILED && mob.work.active()) {
            releaseHandler();
            mob.work.taskFailed();
            reset();
            return;
        }
        if (status == TaskHandler.Status.FAILED && mob.journey.running()) {
            mob.consoleDebug("journey task failed: " + t.spec() + " tries=" + t.tries() + " at " + mob.blockPosition().toShortString());
            releaseHandler();
            mob.journey.fail();
            reset();
            return;
        }
        // FAILED: den Grund hat der Handler selbst geloggt bzw. gesagt
        if (status == TaskHandler.Status.DONE) skipStreak = 0;
        done();
    }

    /** Gemeinsame Navigation/Stuck-Erkennung für Handler. */
    private final class Ctx implements TaskContext {
        @Override public CompanionEntity mob() { return mob; }
        @Override public ServerLevel level() { return (ServerLevel) mob.level(); }
        @Override public boolean approach(BlockPos p, double reachSqr) { return CompanionTaskGoal.this.approach(p, reachSqr); }
        @Override public boolean approachEntity(net.minecraft.world.entity.Entity e, double reachSqr) {
            return CompanionTaskGoal.this.approachEntity(e, reachSqr);
        }
        @Override public boolean approachGoal(dev.begleiter.nav.Goal g, BlockPos key) { return CompanionTaskGoal.this.approachGoal(g, key); }
        @Override public boolean useNewPathing(BlockPos p) { return CompanionTaskGoal.this.useNewPathing(p); }
        @Override public void trackMove() { CompanionTaskGoal.this.trackMove(); }
        @Override public void resetStuck() { stuck = 0; }
        @Override public void capStuck(int max) { stuck = Math.min(stuck, max); }
        @Override public boolean grabPassingOres(Task t) { return CompanionTaskGoal.this.grabPassingOres(level(), t); }
        @Override public void failChain() { CompanionTaskGoal.this.failChain(); }
        @Override public dev.begleiter.task.MiningKnowledge mining() { return mining; }
        @Override public boolean guardedSupply() { return CompanionTaskGoal.this.guardedSupply(); }
        @Override public void abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason reason) {
            CompanionTaskGoal.this.abortAutonomousPlan(reason);
        }
    }

    /**
     * Läuft zum Ziel mit der neuen Wegfindung; true, wenn in Reichweite. Unter Wasser, bei Zielen unter Wasser und kurz
     * nach "unerreichbar" hilft die normale Mob-Navigation aus.
     */
    private boolean approach(BlockPos p, double reachSqr) {
        approachTarget = p;
        if (guardedSupply() && (mob.onGround() || mob.isInWater())
                && mob.level() instanceof ServerLevel sl && mob.memory.dangerous(sl, p, 2)) {
            abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason.ROUTE_UNPROVEN);
            return false;
        }
        Vec3 c = Vec3.atCenterOf(p);
        if (mob.distanceToSqr(c) <= reachSqr) {
            mob.nav.stop();
            return true;
        }
        mob.swim.noteGoal(p);
        if (guardedSupply() && !useNewPathing(p)) {
            abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason.ROUTE_UNPROVEN);
            return false;
        }
        if (useNewPathing(p)) return approachNew(p, reachSqr);
        if (mob.tickCount % 10 == 0) {
            boolean ok = mob.getNavigation().moveTo(c.x, p.getY(), c.z, 1.1);
            var path = mob.getNavigation().getPath();
            if (ok && path != null && path.canReach()) noPath = 0; else noPath++;
        }
        trackMove();
        return false;
    }
    /**
     * Neue Wegfindung: Ziel = Füße höchstens √reachSqr Blöcke vom Zielblock. Unerreichbar zählt wie Feststecken (die
     * Aufgabe wird übersprungen bzw. gräbt sich durch), angekommen gilt als in Reichweite.
     */
    private boolean approachNew(BlockPos p, double reachSqr) {
        // Netherportal: wirklich hineinstellen (davor stehen reicht nicht). Ungeladen: nicht nachsehen (lädt sonst den Chunk)
        if (mob.level().hasChunkAt(p) && mob.level().getBlockState(p).is(Blocks.NETHER_PORTAL))
            return approachGoal(new dev.begleiter.nav.Goal.Block(p), p);
        int rSq = Math.max(1, (int) reachSqr);
        return approachGoal(new dev.begleiter.nav.Goal.Near(p.getX(), p.getY(), p.getZ(), rSq), p);
    }

    /** Ein Ziel mit der neuen Wegfindung ansteuern; key = Block, für den bei "unerreichbar" die alte Navigation einspringt. */
    private boolean approachGoal(dev.begleiter.nav.Goal g, BlockPos key) {
        var r = mob.nav.goTo(g);
        if (r == dev.begleiter.nav.Navigator.Result.ARRIVED) return true;
        if (r == dev.begleiter.nav.Navigator.Result.UNREACHABLE) {
            if (guardedSupply()) {
                abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason.ROUTE_UNPROVEN);
                return false;
            }
            // die neue Suche kann (noch) nicht schwimmen o.ä.: für dieses Ziel eine Weile die alte Navigation
            vanillaFor = key.immutable();
            vanillaUntil = mob.tickCount + 400;
            mob.nav.stop();
        }
        return false;
    }

    /** Ziel, für das die neue Wegfindung aufgab, und bis wann dafür die alte Navigation läuft. */
    private BlockPos vanillaFor;
    private int vanillaUntil;

    /**
     * Neue Wegfindung nicht unter Wasser (sie schwimmt nur an der Oberfläche) und nicht für ein Ziel, an dem sie gerade
     * scheiterte.
     */
    private boolean useNewPathing(BlockPos p) {
        if (mob.isUnderWater()) { mob.nav.stop(); return false; }
        // Ziel unter Wasser (Seegrund): alte Navigation taucht. Lava-Ziele (Wasser drauf gießen) gehen mit der neuen.
        // Ziel in ungeladenem Chunk: unbekannt, nicht nachladen; die neue Wegfindung kennt "ungeladen".
        if (!mob.level().hasChunkAt(p)) return !(p.equals(vanillaFor) && mob.tickCount < vanillaUntil);
        if (mob.level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)
                || mob.level().getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER)) return false;
        return !(p.equals(vanillaFor) && mob.tickCount < vanillaUntil);
    }

    /** Letzte Position des verfolgten Wesens, für die geplant wurde (neu planen erst, wenn es sich deutlich bewegt). */
    private BlockPos entityAnchor;

    private boolean approachEntity(net.minecraft.world.entity.Entity e, double reachSqr) {
        if (mob.distanceToSqr(e) <= reachSqr) {
            mob.nav.stop();
            return true;
        }
        BlockPos at = e.blockPosition();
        mob.swim.noteGoal(at);
        if (entityAnchor == null || entityAnchor.distSqr(at) > 4) entityAnchor = at.immutable();
        if (useNewPathing(entityAnchor)) {
            // Ein Blockziel gilt bis zu einem Block vor seiner Mitte als erreicht. Diesen Rundungsrand innerhalb der
            // echten Reichweite lassen, sonst bleibt er knapp davor stehen und handelt nie.
            int rSq = Math.max(0, (int) Math.pow(Math.max(0, Math.sqrt(reachSqr) - 1), 2));
            // angekommen, aber es ist weitergegangen: nächsten Tick zur neuen Stelle
            var result = mob.nav.goToMoving(new dev.begleiter.nav.Goal.Near(entityAnchor.getX(), entityAnchor.getY(), entityAnchor.getZ(), rSq), e.getUUID());
            if (result == dev.begleiter.nav.Navigator.Result.ARRIVED)
                entityAnchor = at.immutable();
            else if (result == dev.begleiter.nav.Navigator.Result.UNREACHABLE) {
                if (guardedSupply()) abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason.ROUTE_UNPROVEN);
                else { vanillaFor = entityAnchor.immutable(); vanillaUntil = mob.tickCount + 400; mob.nav.stop(); }
            }
            return false;
        }
        if (mob.tickCount % 10 == 0) {
            if (mob.getNavigation().moveTo(e, 1.2)) noPath = 0; else noPath++;
        }
        trackMove();
        return false;
    }
    /** Misst jede Sekunde, ob er sich bewegt hat. Nicht von der Stelle gekommen -> springen, dann graben, dann überspringen. */
    private void trackMove() {
        if (mob.tickCount % 20 != 0) return;
        Vec3 pos = mob.position();
        boolean moved = lastPos != null && pos.distanceToSqr(lastPos) >= 0.2;
        // Mit Ziel: bewegt er sich, kommt aber seit 10 s nicht näher (läuft im Kreis), zählt das auch als Feststecken
        if (active != null && active.pos() != null) {
            double d = Math.sqrt(active.pos().distSqr(mob.blockPosition()));
            if (d < bestDist - 0.9) { bestDist = d; bestDistTick = mob.tickCount; }
            else if (mob.tickCount - bestDistTick > 200) moved = false;
        }
        if (lastPos != null && !moved) stuck += 20; else stuck = Math.max(0, stuck - 20);
        lastPos = pos;
        if (noPath >= 2) { stuck += 20; noPath = 0; }
        if (stuck >= 40 && stuck < 100) mob.getJumpControl().jump();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel sl)) return;
        if (!mob.work.runnable(sl)) { releaseHandler(); reset(); return; }
        Task t = mob.currentTask();
        if (t == null) { releaseHandler(); return; }
        if (!mob.work.active() && !mob.scopedStockWork() && mob.tickCount % 10 == 0 && !mob.isPillarActive()
                && mob.orders.droppedBelowDigPlan()) { releaseHandler(); reset(); return; }
        if (t != active) {
            active = t;
            reset();
            switch (t.type()) {
                case BREAK, PLACE, CRAFT, MOVE_TO, COLLECT -> { }
                // MINE erneuert sich nach jedem Grabstück: nur loggen, wenn sich die Suche ändert
                case MINE -> {
                    String sig = t.spec().toString();
                    if (!sig.equals(lastMineLog)) mob.logInfo(CompanionTexts.tr("handler.goal.task", CompanionTexts.task(t)));
                    lastMineLog = sig;
                }
                default -> { mob.logInfo(CompanionTexts.tr("handler.goal.task", CompanionTexts.task(t))); lastMineLog = null; }
            }
        }
        if (stuck >= 120) { // kommt nicht weiter (die Wegfindung gräbt/baut schon selbst, ohne Erfolg)
            if (mob.journey.running()) {
                releaseHandler();
                mob.journey.fail();
                reset();
                return;
            }
            if (mob.work.active()) {
                if (t.spec() instanceof TaskSpec.Break b && b.scoped()) mob.noteGatherMiss(sl, b.pos());
                releaseHandler();
                mob.work.taskFailed();
                reset();
                return;
            }
            if (mob.scopedStockWork()) {
                if (t.spec() instanceof TaskSpec.Break b) mob.noteGatherMiss(sl, b.pos());
                done();
                return;
            }
            if (mob.hasRecipeRequest() && approachTarget != null && (t.spec() instanceof TaskSpec.Craft
                    || t.spec() instanceof TaskSpec.Smelt || t.spec() instanceof TaskSpec.Process)) {
                var failed = approachTarget;
                mob.markStationUnreachable(failed);
                var reason = CompanionTexts.tr("order.station_unreachable", failed.toShortString());
                String station = switch (t.spec()) {
                    case TaskSpec.Craft ignored -> "crafting_table";
                    case TaskSpec.Smelt ignored -> "furnace";
                    case TaskSpec.Process p -> {
                        var recipe = CompanionRecipes.processingRecipe(sl, p.recipe());
                        yield recipe == null ? null : recipe.station();
                    }
                    default -> null;
                };
                if (station != null && mob.findOrderStation(station, 24) != null) {
                    mob.replaceCurrent(List.of(t.retry()));
                    releaseHandler();
                    reset();
                    return;
                }
                if (mob.recoverRecipeProduction(reason)) {
                    releaseHandler();
                    reset();
                    return;
                }
            }
            // Werkbank unerreichbar (Schlucht, Wasser ...): sie meiden und eine eigene hinstellen
            if (t.spec() instanceof TaskSpec.Craft craft && t.tries() < 3 && !"#planks_from_logs".equals(craft.item())) {
                BlockPos table = mob.findStation("crafting_table", 24);
                if (table != null) {
                    mob.markStationUnreachable(table);
                    mob.logInfo(CompanionTexts.tr("handler.goal.table_unreachable", table.toShortString()));
                    // direkt eine eigene (tragbare) hinstellen, nicht die nächste fremde suchen
                    mob.replaceCurrent(List.of(Task.station("crafting_table", true), t.retry()));
                    reset();
                    return;
                }
            }
            mob.logWarn(CompanionTexts.tr("handler.goal.skipped", CompanionTexts.task(t)));
            if (++skipStreak == 3) mob.problem(CompanionTexts.tr("handler.goal.repeated_failure"));
            if (t.type() == TaskType.FETCH) mob.releaseFetch();
            if (t.type() == TaskType.REPAIR) mob.craftFailedUntil.put("#repair", mob.tickCount + 6000);
            mob.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, t);
            if (mob.hasRecipeRequest()) {
                releaseHandler();
                if (t.spec() instanceof TaskSpec.Take take && mob.orders.isPlanningHomeOrder()) {
                    mob.noteGatherMiss(sl, take.pos());
                    mob.recoverRecipeProduction(CompanionTexts.tr("request.paused", CompanionTexts.task(t)));
                } else if (t.spec() instanceof TaskSpec.Take take)
                    mob.orders.recoverRecipeSource(take.item(), CompanionCraft.count(mob, take.item()) + take.count(), take.pos());
                else mob.pauseRecipeRequest(CompanionTexts.tr("request.paused", CompanionTexts.task(t)));
                reset();
                return;
            }
            if (mob.goal == CompanionNeeds.Need.SUPPLY || mob.goal == CompanionNeeds.Need.ORDER) {
                mob.planFeedback.finish(mob, false);
                mob.cancelAll(); // abhängige Folgeaufgaben nicht ausführen, wenn die Voraussetzung unerreichbar war
                mob.plannerCooldownUntil = mob.tickCount + 200;
                reset();
                return;
            }
            done();
            return;
        }
        // er ist schnell vorbei: oft nach Erzen am Weg schauen
        if (mob.tickCount % 5 == 0 && grabPassingOres(sl, t)) return;
        try {
            TaskHandler handler = slot.sync(t, ctx);
            // jeder Aufgabentyp hat einen Handler (TaskHandlers); fehlt einer, nicht still hängen bleiben
            if (handler == null) throw new IllegalStateException("no handler for " + t.type());
            apply(t, handler.tick(ctx));
        } catch (RuntimeException ex) {
            mob.problem(CompanionTexts.tr("handler.goal.error", CompanionTexts.task(t), CompanionTexts.literal(String.valueOf(ex))));
            StackTraceElement[] st = ex.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) mob.logError(CompanionTexts.literal("  at " + st[i]));
            if (mob.journey.running()) {
                releaseHandler();
                mob.journey.fail();
                reset();
                return;
            }
            if (mob.work.active()) {
                releaseHandler();
                mob.work.taskFailed();
                reset();
                return;
            }
            if (mob.hasRecipeRequest()) {
                releaseHandler();
                mob.pauseRecipeRequest(CompanionTexts.tr("request.paused", CompanionTexts.task(t)));
                reset();
                return;
            }
            done();
        }
    }

    // ------------------------------------------------------------------ Erze am Weg, Abbruch

    /**
     * Unterwegs beim Graben/Laufen (Treppe, Stollen, Weg zu einem Ziel): sieht er freiliegende Erze in der Nähe,
     * baut er sie zwischendurch ab und läuft dann weiter. Nicht beim Bauen, Bäumefällen oder mit vollem Inventar.
     */
    private boolean grabPassingOres(ServerLevel sl, Task t) {
        if (mob.work.active() || mob.scopedStockWork() || mob.hasRecipeRequest()) return false;
        if (t.type() != TaskType.MINE && t.type() != TaskType.MOVE_TO && t.type() != TaskType.TRAVEL
                && !(t.type() == TaskType.BREAK && sl.hasChunkAt(t.pos()) && !sl.getBlockState(t.pos()).is(BlockTags.ORES))) return false;
        if (mob.isPillarActive() || !mob.items.canMakeRoomFor(Items.RAW_IRON, true)) return false;
        boolean mining = false;
        for (Task q : mob.peekTasks(64)) {
            if (q.type() == TaskType.BUILD || q.type() == TaskType.TREE) return false;
            if (q.type() == TaskType.MINE || q.type() == TaskType.GATHER) mining = true;
        }
        if (!mining && !mob.isAutonomous()) return false; // dein "komm her" o.ä.: keine Umwege
        // Nur was er von hier aus erreicht: kein Umweg, der laufende Weg (Treppe, Gerüst) passt danach noch
        List<Task> ores = this.mining.bonusOres(sl, null, REACH_SQR);
        if (ores.isEmpty()) return false;
        for (int i = ores.size() - 1; i >= 0; i--) mob.pushFront(ores.get(i));
        return true;
    }

    /**
     * Nicht gefunden: alle folgenden Aufgaben verwerfen (Schmelzen/Craften hängen davon ab) und den Planer
     * kurz später neu entscheiden lassen, statt sinnlose Folgeaufgaben abzuarbeiten.
     */
    private void failChain() {
        mob.consoleDebug("fail chain: " + (mob.currentTask() == null ? "-" : mob.currentTask().spec()) + " at " + mob.blockPosition().toShortString());
        if (mob.journey.running()) {
            releaseHandler();
            mob.journey.fail();
            reset();
            return;
        }
        if (mob.hasRecipeRequest()) {
            releaseHandler();
            mob.pauseRecipeRequest(CompanionTexts.tr("request.paused", CompanionTexts.task(mob.currentTask())));
            reset();
            return;
        }
        mob.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.PLAN_FAILED, mob.currentTask());
        mob.planFeedback.finish(mob, false);
        mob.cancelAll();
        mob.plannerCooldownUntil = mob.tickCount + 200;
        reset();
    }

    private boolean guardedSupply() {
        return dev.begleiter.ai.CompanionDecisionEngine.enabled() && mob.goal == CompanionNeeds.Need.SUPPLY;
    }

    private void abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason reason) {
        mob.reportAutonomousFailure(reason, mob.currentTask());
        mob.planFeedback.finish(mob, false);
        mob.cancelAll();
        mob.nav.stop();
        mob.plannerCooldownUntil = mob.tickCount + 400;
        reset();
    }
}
