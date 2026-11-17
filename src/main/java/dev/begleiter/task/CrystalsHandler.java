package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/**
 * CRYSTALS: Endkristalle abschießen, die er sehen kann (sonst heilen sie den Drachen). Kristalle in Käfigen trifft ein
 * Pfeil nicht – die lässt er aus. Fertig, wenn keiner mehr sichtbar ist; tries zählt Standortwechsel.
 */
final class CrystalsHandler implements TaskHandler {
    private final Task task;

    CrystalsHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        if (mob.findItem(Items.BOW) < 0 || mob.findItem(Items.ARROW) < 0) {
            mob.problem(CompanionTexts.tr("handler.crystals.no_bow"));
            return Status.FAILED;
        }
        var crystals = mob.endCrystals(160);
        if (crystals.isEmpty()) {
            mob.logInfo(CompanionTexts.tr("handler.crystals.none"));
            return Status.DONE;
        }
        for (var cr : crystals) {
            if (mob.distanceToSqr(cr) > 64 * 64 || !mob.hasLineOfSight(cr)) continue;
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(cr);
            if (mob.shootArrow(cr)) mob.logInfo(CompanionTexts.tr("handler.crystals.shot", cr.blockPosition().toShortString()));
            return Status.RUNNING;
        }
        // keiner in Sicht: Richtung Inselmitte gehen, von dort sieht man die Türme; nach einer Weile aufgeben
        if (task.tries() >= 6) {
            mob.logInfo(CompanionTexts.tr("handler.crystals.hidden"));
            return Status.FAILED;
        }
        BlockPos center = new BlockPos(0, mob.blockPosition().getY(), 0);
        mob.replaceCurrent(List.of(Task.moveTo(center.offset(mob.getRandom().nextInt(41) - 20, 0, mob.getRandom().nextInt(41) - 20), 16),
                task.retry()));
        return Status.REPLACED;
    }
}
