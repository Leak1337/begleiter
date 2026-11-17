package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionDoors;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.Resources;

/** DOOR: Tür einsetzen (pos = untere Hälfte, Blickrichtung). Ohne Tür im Inventar: erst herstellen (tries zählt). */
final class DoorHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private final TaskSpec.Door door;

    DoorHandler(Task task) {
        this.task = task;
        this.door = (TaskSpec.Door) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        BlockPos p = door.pos();
        if (CompanionDoors.isDoor(sl, p)) return Status.DONE;
        if (!CompanionDoors.canPlace(sl, p)) {
            mob.logWarn(CompanionTexts.tr("handler.door.no_space", p.toShortString()));
            if (mob.goal == null) mob.tell(CompanionTexts.tr("handler.door.requirements", p.toShortString()));
            return Status.FAILED;
        }
        int slot = CompanionDoors.doorSlot(mob);
        if (slot < 0) {
            if (task.tries() >= 2) {
                ctx.say(CompanionTexts.tr("handler.door.no_recipe"));
                return Status.FAILED;
            }
            List<Task> pre = new ArrayList<>();
            if (CompanionCraft.count(mob, "#planks") + CompanionCraft.count(mob, "#logs") * 4 < 6)
                pre.add(Task.gather(Resources.WOOD.key(), 2));
            pre.add(Task.craft("oak_door", 3));
            pre.add(task.retry());
            mob.replaceCurrent(pre);
            return Status.REPLACED;
        }
        if (!ctx.approach(p, REACH_SQR)) return Status.RUNNING;
        // nicht in der Türöffnung stehen bleiben
        if (mob.getBoundingBox().intersects(new AABB(p).expandTowards(0, 1, 0))) {
            Direction f = CompanionDoors.direction(door.facing());
            BlockPos side = p.relative(f.getOpposite(), 2);
            mob.getNavigation().moveTo(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, 1.1);
            ctx.trackMove();
            return Status.RUNNING;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
        if (!CompanionDoors.place(sl, mob, p, CompanionDoors.direction(door.facing()), slot)) {
            mob.logWarn(CompanionTexts.tr("handler.door.unconfirmed", p.toShortString()));
            return Status.FAILED;
        }
        mob.logInfo(CompanionTexts.tr("handler.door.installed", p.toShortString()));
        if (mob.goal == null) mob.tell(CompanionTexts.tr("handler.door.done"));
        return Status.DONE;
    }
}
