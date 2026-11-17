package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;

/** FARM: reife Pflanzen ernten und nachpflanzen, leeres Ackerland besäen – bis nichts mehr zu tun ist (pos = Feldmitte). */
final class FarmHandler implements TaskHandler {
    private final Task task;
    private BlockPos target;
    private int since, harvested, planted, progress;
    /** Stellen, an die er nicht kam oder die nichts ergaben. */
    private final Set<BlockPos> skip = new HashSet<>();

    FarmHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        BlockPos center = task.pos() != null ? task.pos() : CompanionJobs.farmCenter(mob);
        if (target == null || !spotValid(sl, mob, target)) {
            target = CompanionJobs.nextFarmTarget(sl, mob, center, skip);
            since = mob.tickCount;
            progress = 0;
        }
        if (target == null) {
            if (harvested + planted == 0) ctx.say(skip.isEmpty() ? CompanionTexts.tr("handler.farm.idle") : CompanionTexts.tr("handler.farm.unreachable"));
            else ctx.say(CompanionTexts.tr("handler.farm.done", harvested, planted));
            if (!skip.isEmpty()) { mob.careScanTick = mob.tickCount + 6000; mob.careMatureCrops = 0; }
            return Status.DONE;
        }
        if (mob.tickCount - since > 300) { // kommt nicht hin: diese Stelle auslassen
            skip.add(target);
            target = null;
            return Status.RUNNING;
        }
        if (!ctx.approach(target, 6.25)) {
            ctx.capStuck(60);
            return Status.RUNNING;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (++progress < 4) return Status.RUNNING;
        progress = 0;
        mob.swingArm();
        int r = CompanionJobs.workFarmSpot(sl, mob, target);
        if (r == 1) {
            harvested++;
            if (sl.getBlockState(target).getBlock() instanceof CropBlock) planted++;
        } else if (r == 2) planted++;
        else skip.add(target);
        target = null;
        return Status.RUNNING;
    }

    private static boolean spotValid(ServerLevel sl, CompanionEntity e, BlockPos p) {
        if (!sl.hasChunkAt(p) || !dev.begleiter.CompanionZones.allows(e, dev.begleiter.CompanionZones.Kind.FIELDS, p)) return false;
        var s = sl.getBlockState(p);
        return CompanionJobs.isMatureCrop(s)
                || (s.isAir() && sl.getBlockState(p.below()).is(Blocks.FARMLAND) && CompanionJobs.seedSlot(e, null) >= 0);
    }
}
