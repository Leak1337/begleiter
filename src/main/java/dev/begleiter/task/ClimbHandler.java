package dev.begleiter.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/** CLIMB: Ziel hoch oben (pos) – so lange Blöcke unter sich setzen, bis es in Reichweite ist. */
final class ClimbHandler implements TaskHandler {
    private final Task task;

    ClimbHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        BlockPos target = task.pos();
        if (mob.isPillarActive()) {
            mob.pillarStep(ctx.level(), CompanionEntity.Pillar.TREE);
            return Status.RUNNING;
        }
        if (mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= 4.3 * 4.3) return Status.DONE;
        BlockPos column = new BlockPos(target.getX(), mob.blockPosition().getY(), target.getZ());
        // "angekommen" der Wegfindung zählt (Blockkoordinaten statt Blockmitte, sonst Stillstand bis zum Überspringen)
        if (mob.distanceToSqr(Vec3.atCenterOf(column)) > 9.0 && !ctx.approach(column, 9.0)) return Status.RUNNING;
        return TreeHandler.climbStep(mob, ctx.level());
    }
}
