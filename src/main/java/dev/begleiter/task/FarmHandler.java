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
        var farm = (TaskSpec.Farm) task.spec();
        if (farm.forage()) return forage(ctx);
        if (farm.plantBerries()) return plantBerries(ctx);
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
            mob.work.missFarm(sl, target);
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
        if (r > 0) mob.work.progress(1);
        if (r == 1) {
            harvested++;
            if (sl.getBlockState(target).getBlock() instanceof CropBlock) planted++;
        } else if (r == 2) planted++;
        else {
            skip.add(target);
            mob.work.missFarm(sl, target);
        }
        target = null;
        return Status.RUNNING;
    }

    private Status forage(TaskContext ctx) {
        var mob = ctx.mob();
        var world = ctx.level();
        BlockPos pos = task.pos();
        if (!world.hasChunkAt(pos)) return Status.FAILED;
        var state = world.getBlockState(pos);
        if (!dev.begleiter.CompanionJourney.ripeBerries(state)) return Status.FAILED;
        if (!ctx.approach(pos, 6.25)) return Status.RUNNING;
        if (!mob.seesBlock(world, pos) || !mob.mayUse(world, pos)) return Status.FAILED;
        int age = state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE);
        int picked = 1 + world.getRandom().nextInt(2) + (age == 3 ? 1 : 0);
        var berries = net.minecraft.world.item.Items.SWEET_BERRIES;
        if (!dev.begleiter.CompanionPrerequisites.fits(mob, berries, picked)) return Status.FAILED;
        var younger = state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1);
        if (!mob.placeConfirmed(world, pos, younger)) return Status.FAILED;
        mob.giveToInventory(new net.minecraft.world.item.ItemStack(berries, picked));
        mob.swingArm();
        ctx.say(CompanionTexts.tr("journey.berries", picked));
        return Status.DONE;
    }

    private Status plantBerries(TaskContext ctx) {
        var mob = ctx.mob();
        var world = ctx.level();
        var pos = task.pos();
        var bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        if (!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || !bush.canSurvive(world, pos)
                || !dev.begleiter.CompanionZones.allows(mob, dev.begleiter.CompanionZones.Kind.FIELDS, pos))
            return Status.FAILED;
        if (!ctx.approach(pos, 6.25)) return Status.RUNNING;
        int berries = mob.findItem(net.minecraft.world.item.Items.SWEET_BERRIES);
        if (berries < 0 || !mob.seesBlock(world, pos.below()) || !mob.mayUse(world, pos)
                || !mob.placeConfirmed(world, pos, bush)) return Status.FAILED;
        mob.getInventory().getItem(berries).shrink(1);
        mob.swingArm();
        return Status.DONE;
    }

    private static boolean spotValid(ServerLevel sl, CompanionEntity e, BlockPos p) {
        if (!sl.hasChunkAt(p) || !dev.begleiter.CompanionZones.allows(e, dev.begleiter.CompanionZones.Kind.FIELDS, p)) return false;
        var s = sl.getBlockState(p);
        return CompanionJobs.isMatureCrop(s)
                || (s.isAir() && sl.getBlockState(p.below()).is(Blocks.FARMLAND) && CompanionJobs.seedSlot(e, null) >= 0);
    }
}
