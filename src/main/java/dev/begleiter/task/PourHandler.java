package dev.begleiter.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionMemory;

/** POUR: Wasser auf eine Lava-Quelle (pos) gießen; das entstandene Obsidian gleich abbauen und einsammeln. */
final class PourHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final TaskSpec.Pour pour;

    PourHandler(Task task) {
        this.pour = (TaskSpec.Pour) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        BlockPos lava = pour.pos();
        if (!sl.getFluidState(lava).isSourceOfType(Fluids.LAVA)) {
            // Gemerkte Lava ist weg (schon Obsidian, abgeflossen): vergessen
            mob.memory.forgetNear(CompanionMemory.Kind.LAVA, null, sl, lava, 1);
            return Status.DONE;
        }
        if (!ctx.approach(lava, REACH_SQR)) return Status.RUNNING;
        mob.getLookControl().setLookAt(Vec3.atCenterOf(lava));
        List<BlockPos> made = mob.pourWater(sl, lava);
        mob.memory.forgetNear(CompanionMemory.Kind.LAVA, null, sl, lava, 1);
        // Das neue Obsidian gleich abbauen (nächstes zuerst) und einsammeln
        made.sort(Comparator.comparingDouble(p -> p.distSqr(mob.blockPosition())));
        List<Task> next = new ArrayList<>();
        for (BlockPos p : made) next.add(Task.breakAt(p, Blocks.OBSIDIAN));
        if (!next.isEmpty()) next.add(Task.collect(false));
        mob.replaceCurrent(next);
        return Status.REPLACED;
    }
}
