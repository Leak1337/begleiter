package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import dev.begleiter.debug.BotDebug.EventKind;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import dev.begleiter.CompanionBuilder;
import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionNeeds;
import dev.begleiter.CompanionPrerequisites;
import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.nav.RoundTripCheck;

/**
 * TAKE: aus der Kiste an pos nehmen (Item-ID, Anzahl). Beim eigenen Vorratsplan mit Intelligence 2.0 erst prüfen, ob
 * Platz ist und Hin- und Rückweg sicher sind; ein Teilfund bricht den Plan ab, statt Folgeschritte zu raten.
 */
final class TakeHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private final TaskSpec.Take take;
    private RoundTripCheck roundTrip;
    private boolean tripInstalled;

    TakeHandler(Task task) {
        this.task = task;
        this.take = (TaskSpec.Take) task.spec();
    }

    /** Ein geprüfter Weg gehört zu dieser Aufgabe: nicht in der nächsten weiterlaufen lassen. */
    @Override
    public void stop(TaskContext ctx) {
        if (tripInstalled) ctx.mob().nav.stop();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        var wanted = CompanionCraft.item(take.item());
        if (ctx.guardedSupply()) {
            if (sl.hasChunkAt(take.pos())) {
                Container contents = mob.containerAt(sl, take.pos());
                if (contents != null && !CompanionPrerequisites.canTake(mob, contents, wanted, take.count())) {
                    ctx.abortAutonomousPlan(DecisionOutcome.Reason.INVENTORY_FULL);
                    return Status.REPLACED;
                }
            }
            Status trip = checkRoundTrip(ctx);
            if (trip != null) return trip;
        }
        if (!ctx.approach(take.pos(), REACH_SQR)) return Status.RUNNING;
        Container box = mob.containerAt(sl, take.pos());
        int got = 0;
        int available = 0;
        if (box != null) {
            for (int i = 0; i < box.getContainerSize(); i++)
                if (box.getItem(i).is(wanted)) available += box.getItem(i).getCount();
            got = CompanionBuilder.takeFrom(mob, box, wanted, take.count());
            mob.swingArm();
            mob.logInfo(EventKind.LOOT, CompanionTexts.tr("handler.take.got", got, CompanionTexts.item(take.item())));
            mob.memory.noteChest(sl, take.pos(), box);
        }
        if (mob.hasRecipeRequest() && got < take.count()) {
            int target = CompanionCraft.count(mob, take.item()) + take.count() - got;
            mob.recoverRecipeSource(take.item(), target, box == null || available <= got ? take.pos() : null);
            if (got < Math.min(available, take.count()))
                mob.pauseRecipeRequest(CompanionTexts.tr("processing.inventory_full", CompanionTexts.item(take.item())));
            return Status.REPLACED;
        }
        if (mob.goal == CompanionNeeds.Need.SUPPLY && got < take.count()) {
            // Eine leere/teilweise Kiste kann abhängiges Craften oder Einlagern nicht erfüllen.
            var reason = box == null ? DecisionOutcome.Reason.SOURCE_MISSING
                    : got < Math.min(available, take.count()) ? DecisionOutcome.Reason.INVENTORY_FULL
                    : available == 0 ? DecisionOutcome.Reason.SOURCE_EMPTY
                    : DecisionOutcome.Reason.SOURCE_PARTIAL;
            mob.reportAutonomousFailure(reason, task);
            mob.planFeedback.finish(mob, false);
            mob.cancelAll();
            mob.plannerCooldownUntil = mob.tickCount + 200;
            return Status.REPLACED;
        }
        if (box == null) {
            mob.logInfo(CompanionTexts.tr("handler.take.no_chest", take.pos().toShortString()));
            return Status.FAILED;
        }
        if (take.count() > 0 && got >= take.count()) mob.planFeedback.sourceSucceeded(mob, task);
        return Status.DONE;
    }

    /** null = Weg freigegeben und installiert, weiter mit approach; sonst der Status für diesen Tick. */
    private Status checkRoundTrip(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        var running = mob.nav.executor();
        if (roundTrip != null && tripInstalled && running != null && !running.safeToCancel(mob)) return null;
        if (roundTrip != null && roundTrip.expired()) {
            if (running != null && !running.safeToCancel(mob)) return null; // erst den Sprung beenden
            mob.nav.stop();
            roundTrip = null;
            tripInstalled = false;
        }
        if (roundTrip == null) {
            mob.getNavigation().stop();
            mob.nav.stop();
            roundTrip = new RoundTripCheck(mob, take.pos(), REACH_SQR);
        }
        var status = roundTrip.step();
        if (status == RoundTripCheck.Status.CHECKING) return Status.RUNNING;
        if (status != RoundTripCheck.Status.SAFE) {
            mob.logInfo(CompanionTexts.tr("handler.take.route_blocked", roundTrip.reasonComponent()));
            ctx.abortAutonomousPlan(status == RoundTripCheck.Status.UNSAFE
                    ? DecisionOutcome.Reason.ROUTE_UNSAFE : DecisionOutcome.Reason.ROUTE_UNPROVEN);
            return Status.REPLACED;
        }
        if (!tripInstalled) {
            mob.nav.followChecked(roundTrip.outbound(), roundTrip.outboundContext(), roundTrip.outboundGoal());
            tripInstalled = true;
        }
        return null;
    }
}
