package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionMemory;

/** WATER: Eimer an einer Wasserquelle füllen (pos = Quelle, sonst suchen/erinnern/erkunden; tries zählt Versuche). */
final class WaterHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private final TaskSpec.Water spec;

    WaterHandler(Task task) {
        this.task = task;
        this.spec = (TaskSpec.Water) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (mob.findItem(Items.WATER_BUCKET) >= 0) return Status.DONE;
        if (mob.findItem(Items.BUCKET) < 0) {
            mob.problem(CompanionTexts.tr("handler.water.no_bucket"));
            ctx.failChain();
            return Status.REPLACED;
        }
        BlockPos water = spec.pos();
        if (water == null || !sl.getFluidState(water).isSourceOfType(Fluids.WATER)) {
            water = mob.findWaterSource(24);
            if (water != null) {
                mob.replaceCurrent(List.of(new Task(new TaskSpec.Water(water), task.tries())));
                return Status.REPLACED;
            }
            if (ctx.mining().goRemembered(sl, CompanionMemory.Kind.WATER, null, 160,
                    new Task(new TaskSpec.Water(null), task.tries()))) return Status.REPLACED;
            if (task.tries() >= 6) {
                mob.problem(CompanionTexts.tr("handler.water.not_found"));
                ctx.failChain();
                return Status.REPLACED;
            }
            Task ex = mob.explorePoint();
            if (ex == null) {
                ctx.failChain();
                return Status.REPLACED;
            }
            mob.replaceCurrent(List.of(ex, new Task(new TaskSpec.Water(null), task.tries() + 1)));
            return Status.REPLACED;
        }
        if (!ctx.approach(water, REACH_SQR)) return Status.RUNNING;
        mob.getLookControl().setLookAt(Vec3.atCenterOf(water));
        if (!mob.fillBucket(sl, water) || mob.findItem(Items.WATER_BUCKET) < 0) {
            mob.logWarn(CompanionTexts.tr("handler.water.unconfirmed", water.toShortString()));
            return Status.FAILED;
        }
        mob.logInfo(CompanionTexts.tr("handler.water.done"));
        return Status.DONE;
    }
}
