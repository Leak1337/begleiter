package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;
import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/**
 * STRONGHOLD: Enderauge werfen, zum Portalraum reisen und hingraben, Augen in die Rahmen setzen, bis das Endportal
 * offen ist. Raum und Portal stehen auf der Entity (endRoomPos/endPortalPos, gespeichert); tries zählt Runden (max. 60).
 */
final class StrongholdHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    /** Kurze Pause zwischen zwei eingesetzten Augen. */
    private int progress;

    StrongholdHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (!mob.inOverworld()) {
            mob.logInfo(CompanionTexts.tr("handler.stronghold.wrong_dimension"));
            return Status.FAILED;
        }
        if (task.tries() >= 60) return fail(ctx, CompanionTexts.tr("handler.stronghold.unreachable"));
        if (mob.endRoomPos == null) {
            if (mob.findItem(Items.ENDER_EYE) < 0) return fail(ctx, CompanionTexts.tr("handler.stronghold.no_eyes"));
            BlockPos room = mob.locateEndRoom();
            if (room == null) return fail(ctx, CompanionTexts.tr("handler.stronghold.not_found"));
            mob.endRoomPos = room;
            mob.throwEye(room);
            mob.tell(CompanionTexts.tr("handler.stronghold.located", room.getX(), room.getZ()));
            mob.replaceCurrent(List.of(Task.travel(room), Task.stronghold()));
            return Status.REPLACED;
        }

        List<BlockPos> frames = mob.portalFrames(16);
        if (!frames.isEmpty()) {
            List<BlockPos> empty = new ArrayList<>();
            for (BlockPos f : frames) if (!CompanionEntity.frameHasEye(sl, f)) empty.add(f);
            if (empty.isEmpty()) {
                BlockPos portal = null;
                for (BlockPos p : BlockPos.betweenClosed(frames.getFirst().offset(-4, -1, -4), frames.getFirst().offset(4, 1, 4)))
                    if (sl.getBlockState(p).is(Blocks.END_PORTAL)) { portal = p.immutable(); break; }
                if (portal == null) return fail(ctx, CompanionTexts.tr("handler.stronghold.no_portal"));
                mob.endPortalPos = portal;
                mob.tell(CompanionTexts.tr("handler.stronghold.open", portal.toShortString()));
                return Status.DONE;
            }
            if (mob.findItem(Items.ENDER_EYE) < 0) return fail(ctx, CompanionTexts.tr("handler.stronghold.missing_eyes", empty.size()));
            empty.sort(Comparator.comparingDouble(f -> f.distSqr(mob.blockPosition())));
            BlockPos f = empty.getFirst();
            if (!ctx.approach(f, REACH_SQR)) return Status.RUNNING;
            mob.getLookControl().setLookAt(Vec3.atCenterOf(f));
            if (++progress % 10 == 0) mob.placeEye(sl, f); // kurz zwischen den Augen warten
            return Status.RUNNING;
        }

        BlockPos room = mob.endRoomPos;
        double dx = room.getX() - mob.getX(), dz = room.getZ() - mob.getZ();
        List<Task> next = new ArrayList<>();
        if (dx * dx + dz * dz > 14 * 14) {
            next.add(Task.travel(room));
        } else if (mob.blockPosition().getY() > room.getY() + 2) {
            if (mob.mineDir == null) mob.mineDir = mob.getDirection();
            List<Task> stairs = mob.staircase(Math.min(8, mob.blockPosition().getY() - room.getY()), mob.mineDir);
            if (stairs.isEmpty()) mob.mineDir = mob.mineDir.getClockWise();
            next.addAll(stairs);
        } else {
            next.add(Task.moveTo(room, 9));
        }
        next.add(task.retry());
        mob.replaceCurrent(next);
        return Status.REPLACED;
    }

    private static Status fail(TaskContext ctx, Component problem) {
        ctx.mob().problem(EventKind.PORTAL, problem);
        ctx.failChain();
        return Status.REPLACED;
    }
}
