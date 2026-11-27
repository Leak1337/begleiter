package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.List;

import net.minecraft.core.BlockPos;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.nav.Goal;

/**
 * TRAVEL: weite Strecke bis in 20 Blöcke Nähe von pos. Die neue Wegfindung übernimmt das Stückeln über Teilwege;
 * kann sie nicht (unter Wasser, kurz nach Scheitern), geht es in Etappen von ~24 Blöcken (tries zählt sie, max. 80).
 */
final class TravelHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Travel travel;
    private boolean prepared;
    private int lastPreparationTick;

    TravelHandler(Task task) {
        this.task = task;
        this.travel = (TaskSpec.Travel) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        BlockPos target = travel.pos();
        double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 20) return Status.DONE;
        if (!mob.journey.isReturningTo(target) && (!prepared || mob.tickCount - lastPreparationTick >= 100)) {
            prepared = true;
            lastPreparationTick = mob.tickCount;
            if (!mob.creativeHelper() && target.distSqr(mob.blockPosition()) > 64 * 64) mob.eatBeforeTrip();
            var readiness = dev.begleiter.CompanionTripPreparation.check(mob, target);
            if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_INPUT
                    && dev.begleiter.CompanionTripPreparation.resupplyFood(mob, ctx.level(), target)) {
                readiness = dev.begleiter.CompanionTripPreparation.check(mob, target);
            }
            if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_TOOL
                    && dev.begleiter.CompanionTripPreparation.resupplyTools(mob, ctx.level())) {
                readiness = dev.begleiter.CompanionTripPreparation.check(mob, target);
            }
            if (!readiness.ready()) {
                // At most two preparation rounds at a nearby known home; recipe deliveries stay reserved.
                if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.INVENTORY_FULL
                        && !mob.hasRecipeRequest() && mob.getHome() != null
                        && mob.getHome().distSqr(mob.blockPosition()) <= 24 * 24 && task.tries() < 2) {
                    mob.logInfo(dev.begleiter.CompanionTexts.tr("trip.deposit"));
                    mob.replaceCurrent(List.of(Task.deliver(true), task.retry()));
                    return Status.REPLACED;
                }
                if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_INPUT
                        && !mob.hasRecipeRequest() && task.tries() < 2 && mob.hasFoodAnimalNearby()) {
                    mob.logInfo(dev.begleiter.CompanionTexts.tr("trip.prepare_food"));
                    mob.replaceCurrent(List.of(Task.hunt(4), task.retry()));
                    return Status.REPLACED;
                }
                // Ziel und abhängige Schritte behalten; das ist Vorbereitung, kein Fehlschlag der Quelle.
                var message = readiness.message();
                mob.setUiFeedback(message);
                mob.logWarn(message);
                if (mob.journey.handleTravelBlock(target, readiness)) return Status.REPLACED;
                mob.pauseWork(message);
                prepared = false; // Explicit resume must recheck even if no world tick elapsed.
                return Status.RUNNING;
            }
        }
        if (ctx.useNewPathing(target))
            return ctx.approachGoal(new Goal.NearXZ(target.getX(), target.getZ(), 20), target) ? Status.DONE : Status.RUNNING;
        if (task.tries() >= 80) {
            mob.problem(CompanionTexts.tr("handler.travel.unreachable", target.toShortString()));
            ctx.failChain();
            return Status.REPLACED;
        }
        double step = Math.min(24, dist);
        int y = mob.blockPosition().getY();
        int wy = y + Math.max(-6, Math.min(6, target.getY() - y));
        BlockPos way = BlockPos.containing(mob.getX() + dx / dist * step, wy, mob.getZ() + dz / dist * step);
        mob.replaceCurrent(List.of(Task.moveTo(way, 16), task.retry()));
        return Status.REPLACED;
    }
}
