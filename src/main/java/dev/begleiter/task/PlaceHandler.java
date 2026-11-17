package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/**
 * PLACE: Block an pos setzen. Fehlt die Sorte und ist sie wertloser Bauschutt, nimmt er eine andere wertlose Sorte.
 * Material wird erst verbraucht, wenn der Block nachweislich in der Welt steht.
 */
final class PlaceHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;

    PlaceHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        BlockPos p = task.pos();
        BlockState cur = sl.getBlockState(p);
        if (cur.is(task.block())) return Status.DONE;
        if (!cur.canBeReplaced()) { // da steht etwas anderes
            mob.logInfo(CompanionTexts.tr("handler.place.occupied", cur.getBlock().getName(), p.toShortString()));
            return Status.FAILED;
        }
        if (!ctx.approach(p, REACH_SQR)) return Status.RUNNING;

        if (mob.getBoundingBox().intersects(new AABB(p))) { // nicht in sich selbst bauen
            // beim Hausbau nach innen ausweichen (Mittelgang), sonst einfach zur Seite
            BlockPos in = mob.houseInside();
            if (in != null && in.distSqr(p) < 20 * 20) {
                BlockPos aisle = new BlockPos(Math.max(in.getX(), Math.min(p.getX() - 1, in.getX() + 4 * mob.houseAnnexes())), in.getY(), in.getZ());
                mob.getNavigation().moveTo(aisle.getX() + 0.5, aisle.getY(), aisle.getZ() + 0.5, 1.1);
            } else mob.getNavigation().moveTo(p.getX() + 3.5, p.getY(), p.getZ() + 0.5, 1.1);
            ctx.trackMove();
            return Status.RUNNING;
        }
        int slot = mob.findItem(task.block().asItem());
        Block placeBlock = task.block();
        // Müll-Block (Weg, Brücke, Tür zumauern): ist die Sorte aus, nimmt er einfach eine andere wertlose Sorte
        if (slot < 0 && CompanionEntity.isJunk(task.block().asItem()) && mob.scaffoldItem() != null) {
            placeBlock = Block.byItem(mob.scaffoldItem());
            slot = mob.findItem(mob.scaffoldItem());
        }
        if (slot < 0) {
            if (mob.hasRecipeRequest()) {
                mob.problem(CompanionTexts.tr("handler.place.missing", task.block().getName()));
                return Status.FAILED;
            }
            if (mob.isAutonomous() && mob.getHouseOrigin() != null) {
                mob.problem(CompanionTexts.tr("handler.place.restock"));
                BlockPos origin = mob.getHouseOrigin();
                mob.cancelAll();
                mob.addTask(Task.house(origin));
                return Status.REPLACED;
            }
            mob.pause(CompanionTexts.tr("handler.place.missing", task.block().getName()));
            return Status.RUNNING; // bleibt vorn, bis der Besitzer "weiter" sagt
        }
        BlockState place = placeBlock.defaultBlockState();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
        mob.swingArm();
        if (!mob.placeConfirmed(sl, p, place)) {
            mob.logWarn(CompanionTexts.tr("handler.place.unconfirmed", p.toShortString(), placeBlock.getName()));
            return Status.FAILED;
        }
        sl.playSound(null, p, place.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
        mob.getInventory().getItem(slot).shrink(1);
        return Status.DONE;
    }
}
