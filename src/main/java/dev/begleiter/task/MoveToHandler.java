package dev.begleiter.task;

import dev.begleiter.CompanionEntity.Task;

/** MOVE_TO: zur Position laufen (Reichweite² 0 = 9). Erze am Ziel noch mitnehmen, dann weiter. */
final class MoveToHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.MoveTo move;
    private int portalWait;
    /** Gerade erst durchs Portal gekommen: erst heraustreten, sonst frischt jeder Tick im Portal die Abklingzeit auf. */
    private net.minecraft.core.BlockPos stepOut;

    MoveToHandler(Task task) {
        this.task = task;
        this.move = (TaskSpec.MoveTo) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (move.portal() != null) {
            var pos = move.pos();
            var wanted = "minecraft:end_portal".equals(move.portal())
                    ? net.minecraft.world.level.block.Blocks.END_PORTAL : net.minecraft.world.level.block.Blocks.NETHER_PORTAL;
            if (ctx.level().hasChunkAt(pos) && !ctx.level().getBlockState(pos).is(wanted)) return Status.FAILED;
            var mob = ctx.mob();
            if (mob.isOnPortalCooldown()) {
                if (stepOut == null) stepOut = outside(ctx.level(), mob.blockPosition(), wanted);
                if (stepOut != null) {
                    portalWait = 0;
                    if (!ctx.approach(stepOut, 1.0)) return Status.RUNNING;
                    return Status.RUNNING; // draußen warten, bis die Abklingzeit vorbei ist
                }
            } else stepOut = null;
            if (ctx.approachGoal(new dev.begleiter.nav.Goal.Block(pos), pos)) {
                if (++portalWait > 160) return Status.FAILED;
                ctx.capStuck(0);
                ctx.mob().motion.move(dev.begleiter.nav.MotionController.Owner.NAVIGATION,
                        pos.getX() + .5, pos.getY(), pos.getZ() + .5, 1, false);
            } else ctx.trackMove();
            return Status.RUNNING; // the entity in the destination dimension replans after the actual transition
        }
        if (move.stockReturn() && (!move.pos().equals(ctx.mob().getHome())
                || ctx.mob().memory.dangerous(ctx.level(), move.pos(), 8))) return Status.FAILED;
        if (!ctx.approach(move.pos(), move.reachSqr() > 0 ? move.reachSqr() : 9.0)) return Status.RUNNING;
        ctx.mob().work.reachedExploration(move.pos());
        // Erze stehen jetzt vorn; diese Aufgabe bleibt dahinter und gilt erst danach als erledigt
        return ctx.mob().duty() == null && ctx.grabPassingOres(task) ? Status.REPLACED : Status.DONE;
    }

    /** Nächster begehbarer Platz neben dem Portal (kein Portalblock, Luft für Füße und Kopf, fester Boden). */
    private static net.minecraft.core.BlockPos outside(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos at, net.minecraft.world.level.block.Block portal) {
        for (int r = 1; r <= 3; r++)
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var p = at.relative(d, r);
                if (!level.hasChunkAt(p)) continue;
                var feet = level.getBlockState(p);
                if (feet.is(portal) || level.getBlockState(p.above()).is(portal)) continue;
                if (!feet.getCollisionShape(level, p).isEmpty() || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) continue;
                var floor = p.below();
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                        || !level.getFluidState(floor).isEmpty()) continue;
                return p;
            }
        return null;
    }
}
