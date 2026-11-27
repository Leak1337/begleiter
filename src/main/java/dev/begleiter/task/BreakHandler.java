package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;
import dev.begleiter.debug.BotDebug.EventKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.Resources;
import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.nav.Goal;

/**
 * BREAK: Block an pos abbauen (block == null: jeden nicht-leeren). Hinlaufen, richtiges Werkzeug, Abbauzeit wie ein
 * Spieler; erledigt erst, wenn die Welt den Abbau bestätigt.
 */
final class BreakHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private final TaskSpec.Break spec;
    private dev.begleiter.nav.RoundTripCheck checkedRoute;
    private boolean checkedPathInstalled;
    private int progress, total;
    /** Risse angezeigt (auch wenn progress für einen Neuanlauf wieder 0 ist). */
    private boolean cracked;

    BreakHandler(Task task) {
        this.task = task;
        this.spec = (TaskSpec.Break) task.spec();
    }

    /** Risse im Block wieder entfernen – auch bei Abbruch, Unterbrechung, Pause oder Überspringen. */
    @Override
    public void stop(TaskContext ctx) {
        if (cracked) ctx.level().destroyBlockProgress(ctx.mob().getId(), task.pos(), -1);
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        BlockPos p = task.pos();
        if (spec.scoped() && (!mob.storage.stockHarvestAllowed(sl, spec) || !sl.hasChunkAt(p))) return Status.FAILED;
        // Ziel in ungeladenem Chunk: erst hingehen, den Block nicht lesen (lädt sonst den Chunk)
        if (!sl.hasChunkAt(p)) { ctx.approach(p, 2.25); return Status.RUNNING; }
        BlockState s = sl.getBlockState(p);
        if (s.isAir()) return Status.DONE;
        if (!mob.storage.harvestAllowed(sl, p, s)) return Status.FAILED;
        if (spec.scoped() && (!mob.hasFreeSlot() || !mob.stockRawBlock(sl, p, s))) return Status.FAILED;
        if (s.getDestroySpeed(sl, p) < 0 || (task.block() != null && !s.is(task.block()))) {
            mob.logInfo(CompanionTexts.tr("handler.break.changed", s.getBlock().getName(), p.toShortString()));
            return Status.FAILED;
        }
        boolean surface = s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.SAND) || s.is(BlockTags.DIRT)
                || s.is(Blocks.GRAVEL) || sl.getBlockState(p.above()).is(Blocks.WATER); // Seegrund: ertaucht
        if (mob.hazardAround(sl, p, surface)) {
            // Lava daneben mit dem Wassereimer löschen (wie ein Spieler am Lavasee), dann nochmal versuchen
            if (!spec.scoped() && quenchLavaAround(mob, sl, p)) return Status.RUNNING;
            mob.logWarn(CompanionTexts.tr("handler.break.fluid", s.getBlock().getName(), p.toShortString()));
            return Status.FAILED;
        }
        if (s.requiresCorrectToolForDrops() && !mob.hasCorrectToolFor(s)) return missingTool(ctx, mob,
                CompanionTexts.tr("handler.break.no_tool", s.getBlock().getName()));
        // Müll (Stein, Erde ... z.B. auf dem Weg) bricht er auch mit vollem Inventar ab – bleibt dann einfach liegen
        if (!mob.hasFreeSlot() && !CompanionEntity.isTrash(s.getBlock().asItem())
                && !mob.items.makeRoomFor(s.getBlock().asItem(), s.is(BlockTags.ORES) || CompanionEntity.isPrecious(s.getBlock().asItem()))) {
            mob.logWarn(CompanionTexts.tr("handler.break.full", CompanionTexts.tr(mob.getHome() == null ? "status.no" : "status.yes")));
            if (mob.hasRecipeRequest()) {
                mob.problem(CompanionTexts.tr("processing.inventory_full", s.getBlock().getName()));
                return Status.FAILED;
            }
            if (mob.getHome() == null) mob.problem(CompanionTexts.tr("handler.inventory.no_storage"));
            if (mob.getHome() != null && mob.tickCount - mob.lastDeliverQueued > 400) {
                mob.lastDeliverQueued = mob.tickCount;
                mob.pushFront(Task.deliver(false));
            } else {
                mob.cancelAll();
                mob.tell(CompanionTexts.tr("handler.inventory.set_home"));
            }
            return Status.REPLACED;
        }
        if (mob.tickCount % 10 == 0) mob.equipToolFor(s);
        if (mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) > REACH_SQR) {
            if (spec.scoped()) {
                if (checkedRoute == null) checkedRoute = new dev.begleiter.nav.RoundTripCheck(mob, p, 9);
                var proof = checkedRoute.step();
                if (proof == dev.begleiter.nav.RoundTripCheck.Status.CHECKING) return Status.RUNNING;
                if (proof != dev.begleiter.nav.RoundTripCheck.Status.SAFE || checkedRoute.expired()) return Status.FAILED;
                if (!checkedPathInstalled) {
                    if (checkedRoute.returning().positions.stream().anyMatch(pos -> pos.below().equals(p))) return Status.FAILED;
                    mob.nav.followChecked(checkedRoute.outbound(), checkedRoute.outboundContext(), checkedRoute.outboundGoal());
                    checkedPathInstalled = true;
                }
                ctx.trackMove();
                return Status.RUNNING;
            }
            // neue Wegfindung: neben den Block stellen (nicht hineingraben)
            if (ctx.useNewPathing(p)) {
                if (ctx.approachGoal(new Goal.Adjacent(p), p)) ctx.trackMove();
                return Status.RUNNING;
            }
            if (ctx.approach(p, 2.25)) ctx.trackMove(); // nah dran, aber zu hoch/weit -> gilt als "kommt nicht weiter"
            return Status.RUNNING;
        }

        if (spec.scoped() && (mob.blockPosition().below().equals(p) || !mob.seesBlock(sl, p))) return Status.FAILED;
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
        if (progress % 5 == 0) mob.equipToolFor(s);
        if (s.requiresCorrectToolForDrops() && !mob.getMainHandItem().isCorrectToolForDrops(s)) {
            mob.equipToolFor(s);
            if (!mob.getMainHandItem().isCorrectToolForDrops(s)) return missingTool(ctx, mob,
                    CompanionTexts.tr("handler.break.not_held", s.getBlock().getName(), mob.getMainHandItem().isEmpty() ? CompanionTexts.tr("handler.hand") : mob.getMainHandItem().getHoverName()));
        }
        if (progress == 0) {
            Component held = mob.getMainHandItem().isEmpty() ? CompanionTexts.tr("handler.hand") : mob.getMainHandItem().getHoverName();
            // gleiche Zeilen hintereinander fasst das Log selbst zusammen
            mob.logInfo(s.is(BlockTags.ORES) || s.is(Blocks.ANCIENT_DEBRIS) || s.is(Blocks.OBSIDIAN) ? EventKind.LOOT : EventKind.NONE,
                    CompanionTexts.tr("handler.break.mining", s.getBlock().getName(), held));
            total = mob.breakTicks(sl, p, s);
        }
        if (progress % 6 == 0) mob.swingArm();
        sl.destroyBlockProgress(mob.getId(), p, Math.min(9, (int) (progress * 10f / total)));
        cracked = true;
        if (++progress < total) return Status.RUNNING;

        boolean removed = false;
        try {
            removed = mob.mineBlock(sl, p, s);
        } catch (RuntimeException ex) {
            mob.logError(CompanionTexts.tr("handler.break.error", p.toShortString(), CompanionTexts.literal(String.valueOf(ex))));
        }
        if (removed) {
            if (!spec.scoped()) return Status.DONE;
            mob.work.stockReturning();
            mob.replaceCurrent(java.util.List.of(new Task(new TaskSpec.MoveTo(mob.getHome(), 9, true))));
            return Status.REPLACED;
        }
        if (spec.scoped()) return Status.FAILED;
        mob.problem(CompanionTexts.tr("handler.break.unconfirmed", p.toShortString()));
        if (mob.goal != null) {
            ctx.abortAutonomousPlan(DecisionOutcome.Reason.PLAN_FAILED);
            return Status.REPLACED;
        }
        mob.pauseWork(CompanionTexts.tr("handler.break.paused"));
        progress = 0; // nach "weiter" neu ansetzen (bleibt vorn)
        return Status.RUNNING;
    }

    private static Status missingTool(TaskContext ctx, CompanionEntity mob, Component problem) {
        if (ctx.guardedSupply()) {
            ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_TOOL);
            return Status.REPLACED;
        }
        mob.problem(problem);
        if (mob.hasRecipeRequest()) return Status.FAILED;
        mob.cancelAll();
        return Status.REPLACED;
    }

    /** Lava neben p löschen, wenn er nah genug ist und einen Wassereimer hat: Quellen werden Obsidian, Fließendes Bruchstein. */
    private static boolean quenchLavaAround(CompanionEntity mob, ServerLevel sl, BlockPos p) {
        if (mob.findItem(Items.WATER_BUCKET) < 0 || mob.blockPosition().distSqr(p) > REACH_SQR) return false;
        boolean any = false;
        for (Direction d : Direction.values()) {
            BlockPos q = p.relative(d);
            var fs = sl.getFluidState(q);
            if (!fs.is(FluidTags.LAVA)) continue;
            mob.getLookControl().setLookAt(Vec3.atCenterOf(q));
            if (fs.isSource()) mob.portals.pourWater(sl, q);
            else mob.worldSet(sl, q, Blocks.COBBLESTONE.defaultBlockState(), 3);
            any = true;
        }
        return any;
    }
}
