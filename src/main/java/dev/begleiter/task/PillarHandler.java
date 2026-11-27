package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.Resources;

/** PILLAR: bis auf Höhe pos.y hochbauen – springen, Block unter sich setzen (bleibt als Weg stehen). */
final class PillarHandler implements TaskHandler {
    private final Task task;

    PillarHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (mob.blockPosition().getY() >= task.pos().getY() && !mob.isPillarActive()) return Status.DONE;
        int r = mob.pillarStep(sl, CompanionEntity.Pillar.PATH);
        if (r < 0 && mob.scaffoldItem() == null && task.tries() < 2) {
            // keine wertlosen Blöcke mehr: schnell Stein/Erde abbauen, dann weiter hochbauen
            mob.logInfo(CompanionTexts.tr("handler.pillar.gather"));
            mob.pillarReset();
            mob.replaceCurrent(List.of(Task.gather(Resources.STONE.key(), 16),
                    task.retry()));
            return Status.REPLACED;
        }
        if (r == 2 && mob.scaffolding.obstacle() != null && task.tries() < 3) {
            // Block über dem Kopf (zum Springen nötig): wegräumen, dann weiter hochbauen
            BlockPos o = mob.scaffolding.obstacle();
            mob.replaceCurrent(List.of(Task.breakAt(o, sl.getBlockState(o).getBlock()),
                    task.retry()));
            return Status.REPLACED;
        }
        if (r < 0 || r == 2) {
            mob.logWarn(CompanionTexts.tr("handler.pillar.failed", mob.blockPosition().toShortString(), r == 2 ? CompanionTexts.tr("handler.pillar.obstacle") : mob.scaffoldItem() == null ? CompanionTexts.tr("handler.pillar.no_blocks") : Component.empty()));
            mob.pillarReset();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }
}
