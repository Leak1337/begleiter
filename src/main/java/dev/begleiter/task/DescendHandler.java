package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/** DESCEND: Gerüst von oben nach unten abbauen (er steht darauf und baut den Block unter sich ab). */
final class DescendHandler implements TaskHandler {
    DescendHandler(Task task) { }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        mob.pruneScaffold(sl);
        if (!mob.hasScaffold()) return Status.DONE;
        BlockPos under = mob.blockPosition().below();
        BlockPos pick = null;
        if (mob.scaffolding.contains(under)) pick = under;
        else {
            double best = -1;
            for (BlockPos p : mob.scaffolding.positions())
                if (mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= 4.3 * 4.3 && p.getY() > best) { best = p.getY(); pick = p; }
        }
        if (pick == null) {
            mob.logWarn(CompanionTexts.tr("handler.descend.unreachable", mob.scaffolding.size()));
            mob.scaffolding.clear();
            return Status.FAILED;
        }
        mob.scaffolding.forget(pick);
        mob.pushFront(Task.breakAt(pick, sl.getBlockState(pick).getBlock()));
        return Status.REPLACED;
    }
}
